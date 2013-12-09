# traffic-police

A routing library for Clojure. Works with Ring out of the box. Can be extended to support any request/response scenario using a method+path semantic for routing. Clout is used for path matching (same as Compojure).

## Installing

Add `[traffic-police "0.3.0"]` to `:dependencies` in your `project.clj`.

## Basic usage

It's convenient to require traffic-police as t.

```clj
(require '[traffic-police :as t])
```

The `handler` function returns a plain ring handler.

```
;; In project.clj
  :plugins [[lein-ring "0.8.5"]
  :ring {:handler myapp.server/lein-ring-app-handler}]

;; In src/myapp/server.clj
(ns myapp.server)

(def app-handler
  (t/handler
    ;; Wraps the handler in middlewares. The middleware will
    ;; execute _after_ routing, so you can do things like
    ;; redirects and other chain breaks without affecting
    ;; other routes than the ones in this list.
    (fn [handler]
      (-> handler
          wrap-params
          wrap-my-custom-login-middleware))
    ;; Routes are nested naturally. The function after the path
    ;; is a precondition, which can return nil and cause a 404.
    ;; Preconditions also work when nested. See more docs below.
    (t/r "/projects" identity
         {:get projects-controller/list-projects
          :post projects-controller/create-project}
         (t/r "/:project-id" projects-controller/get-project-precondition
              {:get projects-controller/get-project
               :put projects-controller/update-project
               :delete projects-controller/deleteproject}
              (t/r "/todos" identity
                   {:get list-todos})))))

(def login-handler
  (t/handler
    ;; No middleware wrapping here
    (t/r "/login" identity
         {:post logins-controller/log-in})))

(def lein-ring-app-handler
  (t/chained-handlers
    app-handler
    login-handler
    (fn [req] {:status 200 :body "Neither app nor login responded."})))
```

* GET /projects - projects-controller/list-projects is called.
* GET /projects/123 - projects-controller/get-project is called. But first, the get-project-precondition function is called. This function will access (-> req :url-params :project-id) and find the project in the database. If this function returns nil, routing will halt. If it returns the full request map, routing continues.

```clj
(defn get-project-precondition
  [req]
  ;; Returns nil if ask-db-for-project returns nil
  (if-let [project (ask-db-for-project (-> req :url-params :project-id))]
    ;; Return full req but with project assoced onto it, so we don't have to
    ;; read it form the db again
    (assoc req :project project)))

;; No need to do any 404 checks here, the precondition takes care of that for us!
(defn get-project
  [req]
  {:status 200 :body (str "Project " (-> req :project :name))})
```

## Nested routes

One of the value adds of traffic-police is a resources-like mindset where you can nest paths.

```clj
(require '[traffic-police :as t])

(t/handler
  [(t/r "/things" identity
        {:get (fn [req] {:status 200 :body "Things here!"})}
        (t/r "/subthings" identity
             {:get (fn [req] {:status 200 :body "Subthings here"
              :post some-other-handler-here})}))
   (t/r "/foos/:foo-id/test" identity
        {:post (fn [req] {:status 201 :body "Foos created"})})])
```

In this case, GET /things will call the first handler, and GET /things/subthings will call the second handler. POST /foos/123/test will call the third handler, as it's not nested under /things. The value "123" will be available on the request, in `:route-params`.

## Preconditions

When the URL contains data that needs to be present for the route to match, you can use preconditions to generalize this behaviour.

```clj
(defn get-user-precondition [req]
  "Return nil to halt routing at this point."
  (if-let [user (somehow-get-the-user (-> req :route-params :user-id))]
    (assoc req :user user)))

(defn show-user-handler [req]
  "This is only called if the preconditions returns something truthy!"
  {:status 200 :body (user-to-json (:user req))})

(t/handler
  [(t/r "/users" identity
        {:get list-users-handler
         :post create-user-handler}
        (t/r "/:user-id" get-user-precondition
             {:get show-user-handler
              :put update-user-handler
              :delete delete-user-handler}))])
```

The show-user-handler doesn't have to check if the user actually exists! Since the get-user-precondition returns nil when the user is not found, routing will fail, and the show-user-handler will not be invoked.

## Nesting preconditions

Extending on the user scenario above:

```clj
(defn get-project-precondition [req]
  "We know that (:user req) exists, because of precondition nesting!"
  (if-let [project (find-project-for-user (:user req) (-> req :route-params :project-id))]
    (assoc req :project project)))

(t/handler
  [(t/r "/users" identity
        {:get list-users-handler
         :post create-user-handler}
        (t/r "/:user-id" get-user-precondition
             {:get show-user-handler
              :put update-user-handler
              :delete delete-user-handler}
             (t/r "/projects" identity
                  {:get list-projects}
                  (t/r "/:project-id" get-project-precondition
                       {:get show-project}))))])
```

Now, a GET /users/123/projects/456 will call both preconditoins. If the user 123 does not exist, the project precondition will not be called, and routing will fail (404).


## Middlewares post path matching

A problem with traditional Ring middlewares is that they are always called. Let's say you have a handler that checks if the user is logged in, and redirects to a login page if it's not. What if you want to compose this with another handler that doesn't care about authentication? You're screwed. The authentication middleware will be called before route matching, so it will be called in any case. The only way around this is to ensure that the handler with auth is last in the chain. But this is a severe limitation, what if you want different handlers with different auth semantics in the same handler chain?

traffic-police supports automatically wrapping all your actual handler functions in middlewares, so that the midldewares doesn't run until _after_ the route has matched. The downside to this approach is that you will have multiple instances of a middleware, using more memory and potentially running expensive middleware setups many times. This only happens once when you call `(t/handler)`, though.

```clj
(t/handler
  (fn [handler]
    (-> handler
        wrap-authentication-middleware
        ring.middleware.content-type/wrap-content-type))
  [(t/r "/users" identity
        {:get list-users-handler})])
```

These middlewares will _only run after the routing has succeeded_. This makes it easy to chain multiple handlers and have them be arbitrarily composable, and not depend on being composed in a specific order.

## Handler composition

If you have multiple handlers, there's a convenience function for that. You can compose any handlers, such as the ones created by compojure, or just small functions you in-line.

```clj
(t/chained-handlers
  (t/handler (fn [handler] (-> handler wrap-auth-redirection))
             [(t/r "/users" identity {:get list-users-handler})])
  (t/handler [(t/r "/ping" identity {:get (fn [req] {:status 200 :body "pong"})})])
  my-compojure-route
  (fn [req] (if (= (:whatever req) "foo") {:status 200 :body "whatever was foo"})))
```

This basically just does `(some #(% req) handlers)`, meaning the first handler that returns something ends up being the response, and no further handlers are called.

## Using with something that isn't Ring

A Ring handler is just a function that takes a request and returns a response. Why would we limit ourselves to Ring? Let's create our own arbitrary request thingie.

```clj
(def my-handler
  (t/handler
     [(t/r "/users" identity {:get (fn [] {:my-status "ok"})})]))

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
```

## The value of values

The `(t/r)` macro is optional, it's just there because most editors will indent in a way that makes the nesting more readable. Just pass a raw list if you want to.

```clj
(t/handler
  [(t/r "/things" identity
        {:get (fn [req] {:status 200 :body "Things here!"})})])

;; Equivalent
(t/handler
  [["/things" identity
    {:get (fn [req] {:status 200 :body "Things here!"})}]])
```

## Example: Using with ZeroMQ

TODO: Write this section.

For now, see http://augustl.com/blog/2013/zeromq_instead_of_http/

## License

Copyright © 2013 August Lilleaas

Distributed under the BSD 3-Clause License
