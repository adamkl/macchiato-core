(ns macchiato.middleware.session
  (:require [macchiato.middleware.session.memory :as mem]
            [macchiato.middleware.session.store :as store]
            [cljs.core.async
             :refer [put! take! <! >! chan close!]
             :refer-macros [go]]
            [cljs.core.async.impl.protocols :refer [ReadPort]]))

(defn- session-options
  [options]
  {:store        (options :store (mem/memory-store))
   :cookie-name  (options :cookie-name "macchiato-session")
   :cookie-attrs (merge {:path      "/"
                         :http-only true}
                        (options :cookie-attrs)
                        (if-let [root (options :root)]
                          {:path root}))})

(defn- bare-session-request
  [request {:keys [store cookie-name]}]
  (go
    (let [req-key     (get-in request [:cookies cookie-name :value])
          session     (<! (store/read-session store req-key))
          session-key (if session req-key)]
      (merge request {:session     (or session {})
                      :session/key session-key}))))

(defn session-request
  ([request]
   (session-request request {}))
  ([request options]
   (-> request (bare-session-request options))))

(defn- bare-session-response
  [{:keys [session-cookie-attrs] :as response} {session-key :session/key} {:keys [store cookie-name cookie-attrs]}]
  (go
    (let [new-session-key (if (contains? response :session)
                            (if-let [session (response :session)]
                              (if (:recreate (meta session))
                                (do
                                  (<! (store/delete-session store session-key))
                                  (->> (vary-meta session dissoc :recreate)
                                       (store/write-session store nil)
                                       (<!)))
                                (<! (store/write-session store session-key session)))
                              (if session-key
                                (<! (store/delete-session store session-key)))))
          cookie          {cookie-name
                           (merge cookie-attrs
                                  session-cookie-attrs
                                  {:value (or new-session-key session-key)})}
          response        (dissoc response :session :session-cookie-attrs)]
      (if (or (and new-session-key (not= session-key new-session-key))
              (and session-cookie-attrs (or new-session-key session-key)))
        (update response :cookies merge cookie)
        response))))

(defn session-response
  ([request response options]
   (fn [response-map]
     (go
       (some-> response-map
               (bare-session-response request options)
               <!
               response)))))

(defn
  ^{:macchiato/middleware
    {:id :wrap-session}}
  wrap-session
  ([handler]
   (wrap-session handler {}))
  ([handler options]
   (let [options (session-options options)]
     (fn
       ([request respond raise]
        (let [out (chan)]
          (take!
           (go
             (let [request (<! (session-request request options))
                   maybe-chan (handler request (session-response request respond options) raise)]
               (if (satisfies? ReadPort maybe-chan)
                 (<! maybe-chan)
                 maybe-chan)))
           (fn [result]
             (if (nil? result)
               (close! out)
               (put! out result))))
          out))))))
