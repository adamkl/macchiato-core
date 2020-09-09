(ns macchiato.middleware.session.memory
  "A session storage engine that stores session data in memory."
  (:require [macchiato.middleware.session.store :refer [SessionStore]]
            [cljs.core.async :refer [chan put! close!]]))

(deftype MemoryStore [session-map]
  SessionStore
  (read-session [_ key]
    (let [c (chan)
          data (@session-map key)]
      (if (nil? data)
        (close! c)
        (put! c data))
      c))
  (write-session [_ key data]
    (let [c (chan)
          key (or key (str (gensym)))]
      (swap! session-map assoc key data)
      (put! c key)
      c))
  (delete-session [_ key]
    (let [c (chan)]
      (swap! session-map dissoc key)
      (close! c)
      c)))

(defn memory-store
  "Creates an in-memory session storage engine. Accepts an atom as an optional
  argument; if supplied, the atom is used to hold the session data."
  ([] (memory-store (atom {})))
  ([session-atom] (MemoryStore. session-atom)))
