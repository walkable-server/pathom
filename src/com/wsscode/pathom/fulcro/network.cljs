(ns com.wsscode.pathom.fulcro.network
  (:require [clojure.core.async :refer [go <! >! put! promise-chan close!]]
            [com.wsscode.common.async-cljs :refer [<? go-catch <!p]]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.graphql :as pg]
            [com.wsscode.pathom.connect.graphql :as pcg]
            [fulcro.client.network :as fulcro.network]
            [fulcro.client.primitives :as fp]
            [fulcro.client.mutations :as fm]
            [goog.array :as garray]
            [goog.events :as events]
            [goog.object :as gobj]
            [goog.string :as gstr]
            [cljs.spec.alpha :as s])
  (:import [goog.net XhrIo EventType]))

(comment
  (let [parser (p/async-parser {::p/plugins [(p/env-plugin {::p/reader {:book-css
                                                                        (fn [env]
                                                                          (go-catch
                                                                            (-> (js/fetch "assets/css/books.css") <!p
                                                                                (.text) <!p)))}})]})]
    (go-catch
      (js/console.log "read res" (<? (parser {} [:book-css]))))))

;; EXPERIMENTAL - all features here are experimental and subject to API changes and breakages

(fm/defmutation index-ready [_]
  (action [{:keys [state]}]
    (swap! state assoc ::index-ready? true))
  (refresh [_] [::index-ready?]))

(defn gql-request [{::keys [url]} query]
  (go-catch
    (-> (js/fetch url #js {:method  "post"
                           :headers #js {"content-type" "application/json"}
                           :body    (js/JSON.stringify
                                      #js {:query (if (string? query) query (pcg/query->graphql query))})})
        <!p (.json) <!p
        (js->clj :keywordize-keys true))))

(defn gql-load-index [req]
  (go-catch
    (let [{:keys [data]} (<? (gql-request req (pg/query->graphql pcg/schema-query)))]
      (pcg/index-schema (assoc req ::pcg/schema data)))))

(s/fdef gql-load-index
  :args (s/cat :input (s/keys :req [::url ::pcg/prefix ::pcg/resolver ::pcg/ident-map])))

(defn make-resolver [{::pcg/keys [prefix]
                      ::keys     [url]}]
  (fn graphql-resolver [env ent]
    (go-catch
      (let [q  (pcg/build-query (assoc env ::pcg/prefix prefix) ent)
            gq (pcg/query->graphql q)
            {:keys [data errors]} (<? (gql-request (assoc env ::url url) gq))]
        (-> (pcg/parser-item {::p/entity          data
                              ::p/errors*         (::p/errors* env)
                              ::pcg/base-path     (vec (butlast (::p/path env)))
                              ::pcg/graphql-query gq
                              ::pcg/errors        (pcg/index-graphql-errors errors)}
              q)
            (pcg/pull-idents))))))

(s/fdef make-resolver
  :args (s/cat :input (s/keys :req [::url ::pcg/prefix])))

(defn app-load-index
  "Use this on your started-callback to load the indexes"
  [app gql-env idx-atom]
  (go-catch
    (let [idx (<? (gql-load-index gql-env))]
      (reset! idx-atom idx)
      (fp/transact! (:reconciler app) [`(index-ready {})]))))

;; Local Network

(defrecord LocalNetwork [parser]
  fulcro.network/NetworkBehavior
  (serialize-requests? [_] true)

  fulcro.network/FulcroNetwork
  (send [this edn ok error]
    (go
      (try
        (ok (<? (parser {} edn)))
        (catch :default e
          (error e)))))

  (start [_]))

(defn local-network [parser]
  (map->LocalNetwork {:parser parser}))

;; FN Network, create a network from a simple function

(defrecord FnNetwork [f serialize?]
  fulcro.network/NetworkBehavior
  (serialize-requests? [_] serialize?)

  fulcro.network/FulcroNetwork
  (send [this edn ok error] (f this edn ok error))

  (start [_]))

(defn fn-network
  ([f] (fn-network f true))
  ([f serialize?]
   (map->FnNetwork {:f          f
                    :serialize? serialize?})))

;; Transform Network

(defrecord TransformNetwork [network options]
  fulcro.network/NetworkBehavior
  (serialize-requests? [_] (fulcro.network/serialize-requests? network))

  fulcro.network/FulcroNetwork
  (send [_ edn ok error]
    (let [{::keys [transform-query transform-response transform-error app*]
           :or    {transform-query    (fn [_ x] x)
                   transform-response (fn [_ x] x)
                   transform-error    (fn [_ x] x)}} options
          req-id (random-uuid)
          env    {::request-id req-id
                  ::app        @app*}]
      (if-let [edn' (transform-query env edn)]
        (fulcro.network/send network edn'
          #(->> % (transform-response env) ok)
          #(->> % (transform-error env) error))
        (ok nil))))

  (start [this]
    (fulcro.network/start network)
    this))

(defn transform-network [network options]
  (->TransformNetwork network (assoc options ::app* (atom nil))))

(defn transform-network-init [network app]
  (some-> network :options ::app* (reset! app)))

;; GraphQL Networking

(defn js-name [s]
  (gstr/toCamelCase (name s)))

(defn mutation [{::p/keys [entity js-key-transform]} key params]
  {:action
   (fn []
     (if-let [[field id] (pg/find-id params)]
       (let [new-id (gobj/getValueByKeys entity #js [(js-key-transform key)
                                                     (js-key-transform field)])]
         {:tempids {id new-id}})
       nil))})

(defn gql-ident-reader [{:keys [ast]
                         :as   env}]
  (if (vector? (:key ast))
    (let [e (p/entity env)]
      (let [item (get e (keyword (pg/ident->alias (:key ast))))]
        (p/join item env)))
    ::p/continue))

(defn gql-key->js [name-transform key]
  (if (vector? key)
    (pg/ident->alias key)
    (name-transform key)))

(defn gql-error-reader [{::keys   [graphql-errors]
                         ::p/keys [path js-key-transform]
                         :as      env}]
  (let [js-path (->> path (butlast) (map (partial gql-key->js js-key-transform)) into-array)]
    (->> (filter #(garray/equals (gobj/get % "path") js-path) graphql-errors)
         (p/join-seq env))))

(def parser
  (p/parser {::p/plugins [(p/env-plugin {::p/reader [gql-ident-reader (p/map-reader* {::p/map-key-transform pcg/camel-key})]})]
             :mutate     mutation}))

(defn http [{::keys [url body method headers]
             :or    {method "GET"}}]
  (let [c   (promise-chan)
        xhr (XhrIo.)]
    (events/listen xhr (.-SUCCESS EventType) #(put! c (.getResponseText xhr)))
    (events/listen xhr (.-ERROR EventType) #(put! c %))
    (.send xhr url method body (clj->js headers))
    c))

(defn lift-tempids [res]
  (->> res
       (into {} (map (fn [[k v]]
                       (if (symbol? k)
                         [k (:result v)]
                         [k v]))))))

(defn query [{::keys [url q gql-process-request] :as input}]
  (go-catch

    (let [req (cond-> #::{:url     url
                          :method  "post"
                          :headers {"content-type" "application/json"}
                          :body    (js/JSON.stringify #js {:query (pg/query->graphql q {::pg/js-name js-name})})}
                gql-process-request (gql-process-request))
          [res text] (-> (http req) <?)]
      (if (gobj/get res "error")
        (throw (ex-info (gobj/get res "error") {:query q}))
        (assoc input ::response-data (js/JSON.parse text))))))

(defn join-remote [{::keys [app remote join-root]
                    :keys  [query]}]
  (let [c (promise-chan)]
    (go
      (if-let [network (-> app :networking (get remote))]
        (fulcro.network/send network [{join-root query}] #(put! c (get % join-root)) #(put! c %))
        (do
          (js/console.warn "Invalid remote" {:remote remote})
          (close! c))))
    c))

(defn gql-network-query [{::keys [url q
                                  gql-process-request
                                  gql-process-query
                                  gql-process-env]
                          :or    {gql-process-query identity
                                  gql-process-env   identity}}]
  (go-catch
    #_(let [json   (-> (query #::{:url url :q (gql-process-query q) :gql-process-request gql-process-request}) <? ::response-data)
            errors (gobj/get json "errors")
            data   (gobj/get json "data")]
        (js/console.log "read" data)
        (-> (gql-process-env {::p/entity       data
                              ::graphql-errors errors})
            (parser q) <?
            (cond-> errors (assoc ::graphql-errors (js->clj errors :keywordize-keys true)))
            (lift-tempids)))))

(defrecord Network [settings]
  fulcro.network/NetworkBehavior
  (serialize-requests? [_] true)

  fulcro.network/FulcroNetwork
  (send [this edn ok error]
    (go
      (try
        (ok (<? (gql-network-query (assoc settings ::q edn))))
        (catch :default e
          (js/console.log "Network error:" e)
          (error e)))))

  (start [_]))

(defn graphql-network [settings]
  (map->Network {:settings settings}))

;; Batch Networking

(defn debounce [f interval]
  (let [timer (atom 0)
        calls (atom [])]
    (fn [& args]
      (js/clearTimeout @timer)
      (swap! calls conj args)
      (reset! timer (js/setTimeout #(do
                                      (f @calls)
                                      (reset! calls []))
                      interval)))))

(defn group-mergeable-requests
  "Given a list of requests [query ok-callback error-callback], reduces the number of requests to the minimum by merging
  the requests. Not all requests are mergeable, so this still might output multiple requests."
  [requests]
  (if (seq requests)
    (let [[[q ok err] & tail] requests
          groups [{::query q ::ok [ok] ::err [err]}]]
      (loop [left       tail
             groups     groups
             current    0
             next-cycle []]
        (if-let [[query ok err :as req] (first left)]
          (let [cur-group (get groups current)
                merged    (p/merge-queries (::query cur-group) query)]
            (if merged
              (recur (next left)
                (-> groups
                    (assoc-in [current ::query] merged)
                    (update-in [current ::ok] conj ok)
                    (update-in [current ::err] conj err))
                current
                next-cycle)
              (recur (next left)
                groups
                current
                (conj next-cycle req))))
          (if (seq next-cycle)
            (let [[[q ok err] & tail] next-cycle]
              (recur tail
                (conj groups {::query q ::ok [ok] ::err [err]})
                (inc current)
                []))
            groups))))
    []))

(defn batch-send
  "Setup a debounce to batch network requests. The callback function f will be called with a list of requests to be made
  after merging as max as possible."
  [f delay]
  (debounce #(f (group-mergeable-requests %)) delay))

(defrecord BatchNetwork [send-fn]
  fulcro.network/NetworkBehavior
  (serialize-requests? [_] true)

  fulcro.network/FulcroNetwork
  (send [_ edn ok error] (send-fn edn ok error))
  (start [_]))

(defn batch-network
  "Wraps a network send calls with a debounce that will accumulate, merge and batch send requests in a time frame
  interval."
  ([network] (batch-network network 10))
  ([network delay]
   (let [send-fn (batch-send (fn [reqs]
                               (doseq [{::keys [query ok err]} reqs]
                                 (fulcro.network/send network query #(doseq [f ok] (f %)) #(doseq [f err] (f %)))))
                   delay)]
     (map->BatchNetwork {:send-fn send-fn}))))
