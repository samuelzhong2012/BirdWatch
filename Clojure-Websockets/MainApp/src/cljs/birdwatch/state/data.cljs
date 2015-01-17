(ns birdwatch.state.data
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [birdwatch.util :as util]
            [birdwatch.stats.timeseries :as ts]
            [birdwatch.stats.wordcount :as wc]
            [birdwatch.state.search :as s]
            [tailrecursion.priority-map :refer [priority-map-by]]
            [cljs.core.async :as async :refer [<! put! pipe timeout chan sliding-buffer]]
            [cljs.core.match :refer-macros [match]]))

(enable-console-print!)

;;; Application state in a single atom
;;; Will be initialized with the map returned by initial-state.
;;; Reset to a new clean slate when a new search is started.
(def app (atom {}))

(def qry-chan (chan))
(defn connect-qry-chan [c] (pipe qry-chan c))

(defn swap-pmap
  "swaps item in priority-map"
  [app priority-map id n]
  (swap! app assoc priority-map (assoc (priority-map @app) id n)))

(def sort-orders
  {:by-followers (priority-map-by >)
   :by-retweets (priority-map-by >)
   :by-favorites (priority-map-by >)
   :by-rt-since-startup (priority-map-by >)
   :by-reach (priority-map-by >)
   :by-id (priority-map-by >)
   :words-sorted-by-count (priority-map-by >)})

(defn initial-state
  "function returning fresh application state"
  []
  {:count 0
   :n 10
   :prev-chunks-loaded 0
   :tweets-map {}
   :search-text ""
   :page 1
   :search "*"
   :users-count 0
   :total-tweet-count 0
   :sorted :by-id
   :live true
   :by-followers (priority-map-by >)
   :by-retweets (priority-map-by >)
   :by-favorites (priority-map-by >)
   :by-rt-since-startup (priority-map-by >)
   :by-reach (priority-map-by >)
   :by-id (priority-map-by >)
   :words-sorted-by-count (priority-map-by >)})

(defn append-search-text [s]
  (swap! app assoc :search-text (str (:search-text @app) " " s)))

(defn- add-to-tweets-map!
  "adds tweet to tweets-map"
  [app tweets-map tweet]
  (swap! app
         assoc-in [tweets-map (keyword (:id_str tweet))]
         tweet))

(defn- swap-when-larger
  "swaps item in priority-map when new value is larger than old value"
  [app priority-map rt-id n]
  (when (> n (rt-id (priority-map @app))) (swap-pmap app priority-map rt-id n)))

(defn- add-rt-status!
  "handles original, retweeted tweet"
  [app tweet]
  (if (contains? tweet :retweeted_status)
    (let [state @app
          rt (:retweeted_status tweet)
          rt-id (keyword (:id_str rt))
          rt-count (:retweet_count rt)]
      (swap-when-larger app :by-retweets rt-id rt-count)
      (swap-when-larger app :by-favorites rt-id (:favorite_count rt))
      (swap-pmap app :by-rt-since-startup rt-id (inc (get (:by-rt-since-startup state) rt-id 0)))
      (swap-pmap app :by-reach rt-id (+ (get (:by-reach state) rt-id 0) (:followers_count (:user tweet))))
      (when (> rt-count (:retweet_count (rt-id (:tweets-map state))))
        (add-to-tweets-map! app :tweets-map rt)))))

(defn- add-tweet!
  "increment counter, add tweet to tweets map and to sorted sets by id and by followers"
  [tweet app]
  (let [state @app
        id-str (:id_str tweet)
        id-key (keyword id-str)]
    (swap! app assoc :count (inc (:count state)))
    (add-to-tweets-map! app :tweets-map tweet)
    (swap-pmap app :by-followers id-key (:followers_count (:user tweet)))
    (swap-pmap app :by-id id-key id-str)
    (swap-pmap app :by-reach id-key (+ (get (:by-reach state) id-key 0) (:followers_count (:user tweet))))
    (add-rt-status! app tweet)
    (wc/process-tweet app (:text tweet))))

(defn- init
  "Initialize application start when application starts by providing fresh state
   and setting the :search-text from the URI location hash."
  []
  (reset! app (initial-state))
  (swap! app assoc :search-text (util/search-hash)))

;;; Channels processing section, here messages are taken from channels and processed.

(defn- stats-loop
  "Process messages from the stats channel and update application state accordingly."
  [stats-chan]
  (go-loop []
           (let [[msg-type msg] (<! stats-chan)]
             (match [msg-type msg]
                    [:stats/users-count       n] (swap! app assoc :users-count n)
                    [:stats/total-tweet-count n] (swap! app assoc :total-tweet-count n))
             (recur))))

(defn- prev-chunks-loop
  "Take messages (vectors of tweets) from prev-chunks-chan, add each tweet to application
   state, then pause to give the event loop back to the application (otherwise, UI becomes
   unresponsive for a short while)."
  [prev-chunks-chan]
  (go-loop []
           (let [chunk (<! prev-chunks-chan)]
             (doseq [t chunk] (add-tweet! t app))
             (<! (timeout 50))
             (recur))))

(defn- data-loop
  "Process messages from the data channel and process / add to application state.
   In the case of :tweet/prev-chunk messages: put! on separate channel individual items
   are handled with a lower priority."
  [data-chan]
  (let [prev-chunks-chan (chan)]
    (prev-chunks-loop prev-chunks-chan)
    (go-loop []
             (let [[msg-type msg] (<! data-chan)]
               (match [msg-type msg]
                      [:tweet/new             tweet] (add-tweet! tweet app)
                      [:tweet/missing-tweet   tweet] (add-to-tweets-map! app :tweets-map tweet)
                      [:tweet/prev-chunk prev-chunk] (do
                                                       (put! prev-chunks-chan prev-chunk)
                                                       (s/load-prev app qry-chan))
                      :else ())
               (recur)))))

(defn- cmd-loop
  "Process command messages, e.g. those that alter application state."
  [cmd-chan pub-chan]
  (go-loop []
           (let [[msg-type msg] (<! cmd-chan)]
             (match [msg-type msg]
                    [:toggle-live           _] (swap! app update :live #(not %))
                    [:set-search-text    text] (swap! app assoc :search-text text)
                    [:set-current-page   page] (swap! app assoc :page page)
                    [:set-page-size         n] (swap! app assoc :n n)
                    [:start-search          _] (s/start-search app (initial-state) qry-chan)
                    [:set-sort-order by-order] (swap! app assoc :sorted by-order)
                    [:retrieve-missing id-str] (put! qry-chan [:cmd/missing {:id_str id-str}])
                    [:append-search-text text] (append-search-text text)
                    [:words-cloud n] (put! pub-chan [msg-type (wc/get-words app n)])
                    [:words-bar   n] (put! pub-chan [msg-type (wc/get-words2 app n)])
                    [:ts-data     _] (put! pub-chan [msg-type (ts/ts-data app)])
                    :else ())
             (recur))))

(defn- broadcast-state
  "Broadcast state changes on the specified channel. Internally uses a sliding
   buffer of size one in order to not overwhelm the rest of the system with too
   frequent updates. The only one that matters next is the latest state anyway.
   It doesn't harm to drop older ones on the channel."
  [pub-channel]
  (let [sliding-chan (chan (sliding-buffer 1))]
    (pipe sliding-chan pub-channel)
    (add-watch app :watcher
               (fn [_ _ _ new-state]
                 (put! sliding-chan [:app-state new-state])))))

(defn- init-state
  "Init app state and wire all channels required in the state namespace."
  [data-chan qry-chan stats-chan cmd-chan state-pub-chan]
  (init)
  (stats-loop stats-chan)
  (data-loop data-chan)
  (cmd-loop cmd-chan state-pub-chan)
  (connect-qry-chan qry-chan)
  (broadcast-state state-pub-chan))