(ns re-chain.core
  (:require [clojure.walk :as walk]
            [re-frame.core :as rf]))

(def ^:dynamic *replace-pointers* false)

(def links (atom []))

(defn seqify [x]
  (if (or (sequential? x) (set? x))
    x
    [x]))

(defn step-id [event-id counter]
  (if (= 0 counter)
    event-id
    (keyword
     (str (namespace event-id)
          (if (namespace event-id) "/")
          (name event-id)
          "-" counter))))

(defn replace-pointers [next-event effects]
  (walk/postwalk
    (fn [x]
      (when (= x :kee-frame.core/next)
        (rf/console :warn "Keyword :kee-frame.core/next is deprecated, use :chain/next instead."))
      (if (#{:kee-frame.core/next :chain/next} x)
        next-event
        x))
    effects))

(defn single-valid-link [effects]
  (let [links (->> @links
                   (filter (fn [{:keys [get-dispatch effect-present?]}]
                             (and (effect-present? effects)
                                  (not (get-dispatch effects))))))]
    (when (= 1 (count links))
      (first links))))

(defn dispatch-empty-or-next [effects next-event-id]
  (when (or (not (:dispatch effects))
            (-> effects
                :dispatch
                first
                (= next-event-id)))
    {:get-dispatch :dispatch
     :set-dispatch (fn [effects event] (assoc effects :dispatch event))}))

(defn single-valid-next [next-event-id effects]
  (let [xs (->> @links
                (filter (fn [{:keys [get-dispatch]}]
                          (= next-event-id
                             (-> effects get-dispatch first)))))]
    (when (= 1 (count xs))
      (first xs))))

(defn select-link [next-event-id effects]
  (or
    (single-valid-next next-event-id effects)
    (single-valid-link effects)
    (dispatch-empty-or-next effects next-event-id)
    (throw
      (ex-info "Not possible to select next in chain"
               {:next-id  next-event-id
                :dispatch (:dispatch effects)
                :links    @links}))))

(defn make-event [next-event-id previous-event-params [_ & params]]
  (into [next-event-id] (concat previous-event-params params)))

(defn link-effects [next-event-id event-params effects]
  (if next-event-id
    (if-let [{:keys [set-dispatch get-dispatch]} (select-link next-event-id effects)]
      (set-dispatch effects (make-event next-event-id event-params (get-dispatch effects)))
      effects)
    effects))

(defn effect-postprocessor [next-event-id]
  (fn [ctx]
    (let [event-params (rest (rf/get-coeffect ctx :event))]
      (update ctx :effects #(cond->> %
                                     *replace-pointers* (replace-pointers next-event-id)
                                     true (link-effects next-event-id event-params))))))

(defn chain-interceptor [current-event-id next-event-id]
  (rf/->interceptor
   :id current-event-id
   :after (effect-postprocessor next-event-id)))

(defn conform-named-handlers [handlers]
  (loop [[event-key interceptors-or-handler & rest] handlers
         matches []]
    (if event-key
      (if (fn? interceptors-or-handler)
        (recur rest (conj matches {:id event-key
                                   :fn interceptors-or-handler}))
        (let [[handler & rest] rest]
          (if (fn? handler)
            (recur rest (conj matches {:id           event-key
                                       :interceptors interceptors-or-handler
                                       :fn           handler}))
            (throw (ex-info "No valid handler found for " {:event-key    event-key
                                                           :interceptors interceptors-or-handler
                                                           :handler      handler})))))
      matches)))

(defn collect-named-event-instructions [step-fns]
  (let [chain-handlers (conform-named-handlers step-fns)]
    (->> chain-handlers
         (partition 2 1 [nil])
         (map (fn [[{:keys [id interceptors] :as handler-1} handler-2]]
                (let [next-id (:id handler-2)]
                  (assoc handler-1 :next-id next-id
                                   :interceptors (some-> interceptors seqify)
                                   :event-handler (:fn handler-1)
                                   :interceptor (chain-interceptor id next-id))))))))

(defn conform-handlers [handlers]
  (loop [[interceptors-or-handler & rest] handlers
         matches []]
    (if interceptors-or-handler
      (if (fn? interceptors-or-handler)
        (recur rest (conj matches {:fn interceptors-or-handler}))
        (let [[handler & rest] rest]
          (if (fn? handler)
            (recur rest (conj matches {:interceptors interceptors-or-handler
                                       :fn           handler}))
            (throw (ex-info "Interceptor without matching handler" {:interceptors interceptors-or-handler
                                                                    :handler      handler})))))
      matches)))

(defn collect-event-instructions [key step-fns]
  (let [chain-handlers (conform-handlers step-fns)]
    (->> chain-handlers
         (partition 2 1 [nil])
         (map-indexed (fn [counter [current-handler next-handler]]
                        (let [{:keys [fn interceptors]} current-handler
                              id      (step-id key counter)
                              next-id (when next-handler (step-id key (inc counter)))]
                          {:id            id
                           :next-id       next-id
                           :event-handler fn
                           :interceptors  interceptors
                           :interceptor   (chain-interceptor id next-id)}))))))

(defn register-chain-handlers! [instructions user-interceptors]
  (doseq [{:keys [id event-handler interceptor interceptors]} instructions]
    (rf/reg-event-fx id (into [interceptor] (concat user-interceptors interceptors)) event-handler)))

(defn reg-chain-named*
  "Same as `reg-chain-named`, but with a vector of interceptors as the first parameter. The interceptors specified
  will be appended to each event's interceptors."
  [interceptors & step-fns]
  (let [instructions (collect-named-event-instructions step-fns)]
    (register-chain-handlers! instructions (some-> interceptors seqify))))

(defn reg-chain*
  "Same as `reg-chain`, but with a vector of interceptors as the second parameter. The interceptors specified
  will be appended to each event's interceptors."
  [id interceptors & step-fns]
  (let [instructions (collect-event-instructions id step-fns)]
    (register-chain-handlers! instructions (some-> interceptors seqify))))

(defn configure!
  "re-chain only supports the `dispatch` effect out of the box. To add more effects, call this function at the startup
  of your app.

  Parameters:

  `chain-links`: Vector of maps. Each map describes how to chain together events using a certain effect. The map should
  contain 3 keys:
  - `:effect-present?` : Is the effect present in the effects map returned from an event function?
  - `:get-dispatch` : Try to lookup the dispatch value from the effects map
  - `:set-dispatch` : Set the dispatch value in the effects map

  Usage:
  ```
  (chain/configure! [{:effect-present? (fn [effects] (:http-xhrio effects))
                      :get-dispatch    (fn [effects] (get-in effects [:http-xhrio :on-success]))
                      :set-dispatch    (fn [effects dispatch] (assoc-in effects [:http-xhrio :on-success] dispatch))}])
  ```
  "
  [chain-links]
  (reset! links chain-links))

(defn reg-chain-named
  "Same as `reg-chain`, but with manually named event handlers. Useful when you need more meaningful names in your
  event log.

  Parameters:

  `handlers`: pairs of id and event handler. Optionally with interceptors between id and handler.

  Usage:
  ```
  (k/reg-chain-named

    :load-customer-data
    (fn [ctx [customer-id]]
      {:http-xhrio {:uri \"...\"}})

    :receive-customer-data
     (fn [ctx [customer-id customer-data]]
      (assoc-in ctx [:db :customers customer-id] customer-data)))
  ```"
  [& handlers]
  (apply reg-chain-named* nil handlers))

(defn reg-chain
  "Register a list of re-frame fx handlers, chained together.

  The chaining is done through dispatch inference. https://github.com/Day8/re-frame-http-fx is supported by default,
  you can easily add your own like this: https://github.com/ingesolvoll/kee-frame#configuring-chains-since-020.

  Each handler's event vector is prepended with accumulated event vectors of previous handlers. So if the first handler
  receives [a b], and the second handler normally would receive [c], it will actually receive [a b c]. The purpose is
  to make all context available to the entire chain, without a complex framework or crazy scope tricks.

  Parameters:

  `id`: the id of the first re-frame event. The next events in the chain will get the same id followed by an index, so
  if your id is `add-todo`, the next one in chain will be called `add-todo-1`.

  `handlers`: re-frame event handler functions, registered with `re-frame.core/reg-event-fx`. Interceptors can be
              specified before the handler, same as with normal event handlers.


  Usage:
  ```
  (k/reg-chain
    :load-customer-data

    (fn {ctx [customer-id]]
      {:http-xhrio {:uri    (str \"/customer/\" customer-id)
                    :method :get}})

    (fn [cxt [customer-id customer-data]
      (assoc-in ctx [:db :customers customer-id] customer-data)))
  ```"
  [id & handlers]
  (apply reg-chain* id nil handlers))