(ns re-chain.core-test
  (:require [clojure.test :refer :all]
            [re-chain.core :as chain]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [re-frame.interceptor :refer [->interceptor]])
  (:import (clojure.lang ExceptionInfo)))

(def default-links [{:effect-present? (fn [effects] (:http-xhrio effects))
                     :get-dispatch    (fn [effects] (get-in effects [:http-xhrio :on-success]))
                     :set-dispatch    (fn [effects dispatch] (assoc-in effects [:http-xhrio :on-success] dispatch))}])

(defn insert-marker [m]
  (->interceptor
   :id :insert-marker
   :after (fn [context]
            (assoc-in context [:effects :db :marker] m))))

(deftest utils
  (testing "Can produce next step id with namespaced keyword"
    (is (= :ns/id-2 (chain/step-id :ns/id 2))))
  (testing "Can produce next step id with plain keyword"
    (is (= :keyw-2 (chain/step-id :keyw 2)))))

(deftest interceptors
  (chain/configure! default-links)

  (testing "Inserts dispatch to next"
    (is (= {:dispatch [:next]}
           (chain/link-effects :next [] {}))))

  (testing "Throws when only one potential link and it's taken"
    (is (thrown? ExceptionInfo
                 (chain/link-effects :next [] {:dispatch [:something-bad]}))))

  (testing "Inserts on-success on http"
    (is (= [:next]
           (-> (chain/link-effects :next [] {:http-xhrio {}})
               (get-in [:http-xhrio :on-success])))))

  (testing "Can use special pointer to next action when explicit params are needed"
    (is (= {:dispatch [:next-event :a :b :c]}
           (chain/replace-pointers :next-event {:dispatch [:chain/next :a :b :c]}))))

  (testing "Reports error when on-success or dispatch are specified and none of them point to correct next event"
    (is (thrown? ExceptionInfo
                 (chain/link-effects :next-event [] {:dispatch   [:something]
                                                     :http-xhrio {:on-success [:something-else]}}))))
  (testing "Exactly one event dispatches to next in chain."
    (is (= {:dispatch   [:break-out-of-here]
            :http-xhrio {:get        "cnn.com"
                         :on-success [:next-event]}}
           (chain/link-effects :next-event [] {:dispatch   [:break-out-of-here]
                                               :http-xhrio {:get "cnn.com"}}))))

  (testing "Will pass its parameters on to next in chain"
    (is (= {:dispatch [:next-event 1 2]}
           (-> {:coeffects {:event [:this-event 1 2]}}
               ((chain/effect-postprocessor :next-event))
               :effects)))

    (let [get-effects (fn [] (-> {:coeffects {:event [:previous-event 1 2]}
                                  :effects   {:dispatch [:chain/next 3 4]}}
                                 ((chain/effect-postprocessor :next-event))
                                 :effects))]
      (binding [chain/*replace-pointers* true]
        (is (= {:dispatch [:next-event 1 2 3 4]}
               (get-effects))))
      (is (thrown-with-msg? ExceptionInfo #"Not possible to select next in chain"
             (get-effects))))))

(deftest outer-api
  (testing "Plain chain"
    (let [instructions (chain/collect-event-instructions :my/chain
                                                         [identity
                                                          identity])]
      (is (= [:my/chain :my/chain-1]
             (map :id instructions)))))

  (testing "Bad chain"
    (is (thrown-with-msg? ExceptionInfo #"Interceptor without matching handler"
                          (chain/collect-event-instructions :my/chain
                                                            ["string-should-not-be-here"]))))

  (testing "Chain with interceptors"
    (is (= :debug (-> (chain/collect-event-instructions :my/chain
                                                        [[rf/debug]
                                                         identity])
                      first
                      :interceptors
                      first
                      :id))))

  (testing "Wrong order of interceptors"
    (is (thrown-with-msg? ExceptionInfo #"Interceptor without matching handler"
                          (chain/collect-event-instructions :my/chain
                                                            [[rf/debug] [rf/debug]]))))

  (testing "Interceptors outside sequence"
    (is (= 1 (count (chain/collect-event-instructions :my/chain
                                                      [rf/debug identity])))))

  (testing "Named chain"
    (let [instructions (chain/collect-named-event-instructions
                        [:step-1
                         identity
                         :step-2
                         identity])]
      (is (= [:step-1 :step-2]
             (map :id instructions)))))

  (testing "Bad named chain gives good error message"
    (is (thrown-with-msg? ExceptionInfo #"No valid handler found for "
                          (chain/collect-named-event-instructions
                           [:step-1
                            identity
                             :step-2])))))

(def custom-chain-links [{:effect-present? (fn [effects] (:my-custom-effect effects))
                          :get-dispatch    (fn [effects] (get-in effects [:my-custom-effect :got-it]))
                          :set-dispatch    (fn [effects dispatch] (assoc-in effects [:my-custom-effect :got-it] dispatch))}])

(deftest integration
  (testing "Custom chain links"

    (rf-test/run-test-sync
     (chain/configure! custom-chain-links)
     (rf/reg-fx :my-custom-effect (fn [config] (rf/dispatch (:got-it config))))
     (rf/reg-sub :test-prop :test-prop)
     (chain/reg-chain :test-event
                      (fn [_ _] {:my-custom-effect {}})
                      (fn [_ _] {:db {:test-prop 2}}))
     (rf/dispatch [:test-event])
     (is (= 2 @(rf/subscribe [:test-prop])))))

  (testing "Named chain with interceptor"

    (rf-test/run-test-sync
     (let [counter     (atom 0)
           interceptor (->interceptor
                        :before (fn [context]
                                  (swap! counter inc)
                                  context))]
       (chain/reg-chain-named
        :test-event
        [interceptor]
        (fn [_ _] {})
        :test-event-step-2
        interceptor
        (fn [_ _] nil))
       (rf/dispatch [:test-event])
       (is (= 2 @counter)))))

  (testing "Named chain with common interceptor"

    (rf-test/run-test-sync
     (let [counter     (atom 0)
           interceptor (->interceptor
                        :before (fn [context]
                                  (swap! counter inc)
                                  context))]
       (chain/reg-chain*
        :test/event
        interceptor
        (fn [_ _] nil)
        (fn [_ _] nil))
       (rf/dispatch [:test/event])
       (is (= 2 @counter)))))

  (testing "Chain with interceptor"

    (rf-test/run-test-sync
     (rf/reg-sub :marker :marker)
     (chain/reg-chain
      :test-event
      (fn [_ _] {})
      [(insert-marker 43)]
      (fn [_ _] nil))
     (rf/dispatch [:test-event])
     (is (= 43 @(rf/subscribe [:marker]))))))