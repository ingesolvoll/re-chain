# re-chain

Event chains for [re-frame](https://github.com/Day8/re-frame)

[![Build Status](https://travis-ci.org/ingesolvoll/re-chain.svg?branch=master)](https://travis-ci.org/ingesolvoll/re-chain)

[![Clojars Project](https://img.shields.io/clojars/v/re-chain.svg)](https://clojars.org/re-chain)


## Getting started
Add the leiningen dependency:
```clojure
[re-chain "1.0"]
```

Require core namespace:

```clojure
(require '[re-chain.core :as chain :refer [reg-chain reg-chain-named]])
```

> The following examples assume that you have configured re-chain to recognize effects from [re-frame-http-fx](https://github.com/Day8/re-frame-http-fx). See the configuration section at the bottom.

## The problem

One very common pattern in re-frame is to register 2 events, one for doing a side effect like HTTP, one for handling the response data. Sometimes you need more than 2 events. Creating these event chains is boring and verbose, and you easily lose track of the flow. See an example below:

```clojure      
(re-frame/reg-event-fx :add-customer
                      (fn [_ [_ customer]]
                        {:http-xhrio {:method          :post
                                      :uri             "/customers"
                                      :body            customer-data
                                      :on-success      [:customer-added]}}))

(re-frame/reg-event-db :customer-added
                          (fn [db [_ customer]]
                            (update db :customers conj customer)))
```

If some code ends up in between these 2 close friends, the cost of following the flow greatly increases. Even when they are positioned next to each other, an extra amount of thinking is required in order to see where the data goes.

## Event chains to the rescue

A chain is a list of FX (not DB) type event handlers. 

Through the magic of re-frame `interceptors`, we are able to chain together event handlers without registering them by name. We are also able to infer how to dispatch to next in chain. Here's the above example using a chain:

```clojure      
(reg-chain :add-customer
            
            (fn [_ [customer]]
              {:http-xhrio {:method          :post
                            :uri             "/customers"
                            :body            customer-data}})
            
            (fn [{:keys [db]} [_ added-customer]] ;; Remember: No DB functions, only FX.
              {:db (update db :customers conj added-customer)}))
```

The chain code does the same thing as the event code. It registers the events `:add-customer` and `:add-customer-1` as normal re-frame events. The events are registered with an interceptor that processes the event effects and finds the appropriate `on-success` handler for the HTTP effect. Less work for you to do and less cognitive load reading the code later on.

The chain concept might not always be a good fit, but quite often it does a great job of uncluttering your event ping pong.

## Chain rules
Every parameter received through the chain is passed on to the next step. So the parameters to the first chain function will be appended to the head of the next function's parameters, and so on. The last function called will receive the concatenation of all previous parameter lists. This might seem a bit odd, but quite often you need the id received on step 1 to do something in step 3.

You are allowed to dispatch out of chain, but there must always be a "slot" available for the chain to put its next dispatch.

You can specify your dispatch explicitly using a special keyword as your event id, like this: `{:on-success [:chain/next 1 2 3]}`. The keyword will be replaced by a generated id for the next in chain. 

## But I want to decide the name of my events!

Sometimes you may want to specify your event names, to ease debugging or readability. In that case, use the `reg-chain-named`, like this: 

```clojure
(reg-chain-named :first-id 
                  first-fn 
                  :second-id 
                  second-fn
                  ....)
```

## Customization 

`dispatch` is the only effect supported by default in event chains. Apps that introduce their own effect handlers, 
or use libraries with custom effect handlers, need to tell the chain system how to dispatch using these handlers. 

For example, if you want to use chains for [re-frame-http-fx](https://github.com/Day8/re-frame-http-fx), 
you need to add the following configuration.

```clojure
[{
  ;; Is the effect in the map?
  :effect-present?   (fn [effects] (:http-xhrio effects)) 
  
  ;;  The dispatch set for this effect in the map returned from the event handler
  :get-dispatch (fn [effects] (get-in effects [:http-xhrio :on-success]))
  
  ;; Framework will call this function to insert inferred dispatch to next handler in chain
  :set-dispatch   (fn [effects dispatch] (assoc-in effects [:http-xhrio :on-success] dispatch))  
}]
```

Apply your config like this:

```clojure
(chain/configure!  [chain-config-map-1 chain-config-map-2])
```

## Kee-frame
re-chain was originally a part of [kee-frame](https://github.com/ingesolvoll/kee-frame). Event though it was extracted
out into a separate library, it is still important for kee-frame.
