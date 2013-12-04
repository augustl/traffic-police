# traffic-police

A routing library for Clojure. Works with Ring out of the box. Can be extended to support any request/response scenario using a method+path semantic for routing. Clout is used for path matching (same as Compojure).

## Nested routes

One of the value adds of traffic-police is a resources-like mindset where you can nest paths.

    (require '[traffic-police :as t])
    
    (t/handler
      [["/things" identity
        {:get (fn [req] {:status 200 :body "Things here!"})}
        ["/subthings" identity
         {:get (fn [req] {:status 200 :body "Subthings here"})}]]
       ["/foos/:foo-id/test" identity
        {:post (fn [req] {:status 201 :body "Foos created"})}]])

In this case, GET /things will call the first handler, and GET /things/subthings will call the second handler. POST /foos/123/test will call the third handler, as it's not nested under /things. The value "123" will be available on the request, in `:route-params`.

## Preconditions

When the URL contains data that needs to be present for the route to match, you can use preconditions to generalize this behaviour.

    (defn get-single-user-precondition [req]
      (if-let [user (somehow-get-the-user (-> req :route-params :user-id))]
        (assoc req :user user)))

    (defn show-user-handler [req]
      {:status 200 :body (user-to-json (:user req))})
    
    (t/handler
      [["/users" identity
        {:get list-users-handler
         :post create-user-handler}
        ["/:user-id" get-single-user-precondition
         {:get show-user-handler
          :put update-user-handler
          :delete delete-user-handler}]]])

The show-user-handler doesn't have to check if the user actually exists! Since the get-single-user-precondition returns nil when the user is not found, routing will fail, and the show-user-handler will not be invoked.

This is also great for nesting. /users/123/friends/1 will not route if the user of ID 123 doesn't exist, using the very same function to get the user as /users/123 will.


## Middlewares post path matching

A problem with traditional Ring middlewares is that they are always called. Let's say you have a handler that checks if the user is logged in, and redirects to a login page if it's not. What if you want to compose this with another handler that doesn't care about authentication? You're screwed. The authentication middleware will be called before route matching, so it will be called in any case. The only way around this is to ensure that the handler with auth is last in the chain. But this is a severe limitation, what if you want different handlers with different auth semantics in the same handler chain?

traffic-police supports automatically wrapping all your actual handler functions in middlewares, so that the midldewares doesn't run until _after_ the route has matched. The downside to this approach is that you will have multiple instances of a middleware, using more memory and potentially running expensive middleware setups many times. This only happens once when you call `(t/handler)`, though.

    (t/handler
      (fn [handler]
        (-> handler
            wrap-authentication-middleware
            ring.middleware.content-type/wrap-content-type))
      [["/users" identity
        {:get list-users-handler}]])

These middlewares will _only run after the routing has succeeded_. This makes it easy to chain multiple handlers and have them be arbitrarily composable, and not depend on being composed in a specific order.

## Handler composition

If you have multiple handlers, there's a convenience function for that. You can compose any handlers, such as the ones created by compojure, or just small functions you in-line.

    (t/chained-handlers
      (t/handler (fn [handler] (-> handler wrap-auth-redirection))
                 [["/users" identity {:get list-users-handler}]])
      (t/handler [["/ping" identity {:get (fn [req] {:status 200 :body "pong"})}]])
      my-compojure-route
      (fn [req] (if (= (:whatever req) "foo") {:status 200 :body "whatever was foo"})))

This basically just does `(some #(% req) handlers)`, meaning the first handler that returns something ends up being the response, and no further handlers are called.

## Using with something that isn't Ring

A Ring handler is just a function that takes a request and returns a response. Why would we limit ourselves to Ring? Let's create our own arbitrary request thingie.

    (def my-handler
      (t/handler
        [["/users" identity {:get (fn [] {:my-status "ok")}]]))

    (defrecord CustomRequest [fancy-method nice-path])
    (extend-protocol t/TrafficPoliceRequest
      CustomRequest
      ;; Default gets :request-method
      (get-request-method [req] (:fancy-method req))
      ;; Default is to get :uri or :path-info
      (get-request-path [req] (:nice-path req))
      ;; Default is (assoc req :route-params route-params)
      (assoc-route-params [req route-params] (assoc req :dem-route-params route-params))
      ;; Default returns {:status 405}
      (get-method-not-allowed-response [req] {:my-status "method not allowed"}))

    (my-handler (CustomRequest. :get "/users"))
    ;; {:my-status "ok"}

## Example: Using with ZeroMQ

TODO: Write this section.

For now, see http://augustl.com/blog/2013/zeromq_instead_of_http/

## License

Copyright © 2013 August Lilleaas

Distributed under the BSD 3-Clause License
