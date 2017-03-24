(ns com.cognitect.vase.fern-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [com.cognitect.vase.fern :as fern]
            [instaparse.core :as insta]
            [io.pedestal.interceptor :as i]
            [com.cognitect.vase.literals :as lit]))

(defn map-vals
  [f m]
  (with-meta
    (reduce-kv
     #(assoc %1 %2 (f %3))
     {}
     m)
    (meta m)))

(defn- remove-tempids
  [entities]
  (map (fn [entity] (dissoc entity :db/id)) entities))

(defn- remove-tempids-from-norm
  [norm]
  (update norm :vase.norm/txes remove-tempids))

(deftest parse-fern
  (testing "names"
    (are [input expected] (= expected (fern/parse-string input :qualified-name))
      "example"              'example
      "example.part"         'example.part
      "ns1.ns2/example.part" 'ns1.ns2/example.part
      "question?"            'question?
      "imperative!"          'imperative!
      "numeric001"           'numeric001
      "hyphen-split"         'hyphen-split
      "inner$classlike"      'inner$classlike
      "a100%"                'a100%))

  (testing "params"
    (are [input expected] (= expected (fern/parse-string input :params))
      "params [id1]"
      [:params [:id1]]

      "params [ns/id1]"
      [:params [:ns/id1]]

      "params [id1 id2 id3]"
      [:params [:id1 :id2 :id3]]

      "params [[with-default 0] no-default [string-default \"s\"]]"
      [:params [[:with-default 0] :no-default [:string-default "s"]]]
      ))

  (testing "schema fragments"
    (are [input expected] (= expected (map-vals remove-tempids-from-norm (fern/parse-string input :Schema)))
      "schema example/base"
      {:example/base {:vase.norm/txes ()}}

      "schema example/base
         attribute user/name one string \"This is a doc\""
      {:example/base
       {:vase.norm/txes
        [{:db/ident :user/name,
          :db/valueType :db.type/string,
          :db/cardinality :db.cardinality/one,
          :db.install/_attribute :db.part/db,
          :db/doc "This is a doc"}]}}

      "schema with-toggles
       attribute people.user/username one string identity fulltext no-history \"An attribute with toggles\""
      {:with-toggles
       {:vase.norm/txes
        [{:db/index true,
          :db/unique :db.unique/identity,
          :db/valueType :db.type/string,
          :db/noHistory true,
          :db.install/_attribute :db.part/db,
          :db/fulltext true,
          :db/cardinality :db.cardinality/one,
          :db/doc "An attribute with toggles",
          :db/ident :people.user/username}]}}

      "schema multiple-attributes
         attribute user/id one long \"Numeric ID\"
         attribute user/name one string \"Printable\"
         attribute user/friends many ref \"Connections\""
      {:multiple-attributes
       {:vase.norm/txes
        [{:db/ident :user/id,
          :db/valueType :db.type/long,
          :db/cardinality :db.cardinality/one,
          :db.install/_attribute :db.part/db,
          :db/doc "Numeric ID"}
         {:db/ident :user/name,
          :db/valueType :db.type/string,
          :db/cardinality :db.cardinality/one,
          :db.install/_attribute :db.part/db,
          :db/doc "Printable"}
         {:db/ident :user/friends,
          :db/valueType :db.type/ref,
          :db/cardinality :db.cardinality/many,
          :db.install/_attribute :db.part/db,
          :db/doc "Connections"}]}}))

  (testing "api fragments"
    (are [input expected] (= expected (fern/parse-string input :Api))
      "api example/user"
      [:Api 'example/user]

      "api example/one-route
        get \"/foo\"  one-interceptor"
      [:Api 'example/one-route
       [:Route :get "/foo" ['one-interceptor]]]

      "api example/one-route
        get \"/foo\"  [an-interceptor another-interceptor]"
      [:Api 'example/one-route
       [:Route :get "/foo" ['an-interceptor 'another-interceptor]]]

      "api example/verbs
        get  \"/foo\" one-action
        put  \"/foo\" different-action
        post \"/foo\" other-action"
      [:Api 'example/verbs
       [:Route :get  "/foo" ['one-action]]
       [:Route :put  "/foo" ['different-action]]
       [:Route :post "/foo" ['other-action]]]

      "api multiple/routes
        get \"/users\" list-users
        post \"/users\" create-user
        get \"/users/:id\" show-user
        delete \"/users/:id\" delete-user"
      [:Api 'multiple/routes
       [:Route :get "/users" ['list-users]]
       [:Route :post "/users" ['create-user]]
       [:Route :get "/users/:id" ['show-user]]
       [:Route :delete "/users/:id" ['delete-user]]]))

  (testing "stock interceptors"
    (are [input expected] (= expected (fern/parse-string input :StockInterceptor))
      "respond home-page
         status 200"
      (lit/map->RespondAction {:name :home-page :status 200})

      "respond home-page
         status 209
         params [query lang]
         headers {\"Produced-by\" \"TDD\"
                  \"Yielding\" \"100%\"}"
      (lit/map->RespondAction {:name :home-page :status 209 :params [:query :lang] :headers {"Produced-by" "TDD" "Yielding" "100%"}})

      "respond my-app/page1
         status 200
         params [id]
         edn-coerce [id]
         body (str \"You gave me ID \" id)"
      (lit/map->RespondAction {:name :my-app/page1 :status 200 :params [:id] :edn-coerce [:id] :body '(str "You gave me ID " id)})

      "respond my-app/add1
         params [id]
         edn-coerce [id]
         body (str \"One more is \" (inc id))"
      (lit/map->RespondAction {:name :my-app/add1 :params [:id] :edn-coerce [:id] :body '(str "One more is " (inc id))})

      ;; Can't use anonymous fn body in the test b/c it turns into a
      ;; function value, which we can't compare for equality.
      ;; Anonymous fns work just fine in real usage.
      "respond my-app/complex-body
         body (filter (fn [x] (< 100 %)) (load-scores))"
      (lit/map->RespondAction {:name :my-app/complex-body :body '(filter (fn [x] (< 100 %)) (load-scores))})

      "redirect aname
        status 302
        url \"http://www.google.com/\""
      (lit/map->RedirectAction {:name :aname :status 302 :url "http://www.google.com/"})

      "redirect userpage
         status 302
         headers {\"Extra-Header\" \"Indeed\" \"And-Another?\" \"Thank You\"}
         params [id]
         url (str \"/users/\" id)"
      (lit/map->RedirectAction {:name :userpage
                                :status 302
                                :params [:id]
                                :url '(str "/users/" id)
                                :headers {"Extra-Header" "Indeed" "And-Another?" "Thank You"}})

      "interceptor direct-code
         enter (fn [ctx] :enter)
         leave (fn [ctx] :leave)
         error (fn [ctx] :error)"
      (lit/map->InterceptAction {:name :direct-code :enter '(fn [ctx] :enter) :leave '(fn [ctx] :leave) :error '(fn [ctx] :error)})

      "conform aname
         from this-key
         spec (clojure.spec/and string? not-empty)
         to   that-key"
      (lit/map->ConformAction {:name :aname :from :this-key :to :that-key :spec '(clojure.spec/and string? not-empty)})

      "query single-result
         q      [:find ?email ?name :in $ ?id :where [?e :user/email ?email] [?e :user/id ?id]]
         params [id]
         to     result-key"
      (lit/map->QueryAction {:name   :single-result
                             :query  '[:find ?email ?name :in $ ?id :where [?e :user/email ?email] [?e :user/id ?id]]
                             :params [:id]
                             :to     :result-key})

      "query binding-form
         q [:find ?release-name
            :in $ ?artist-name
            :where [?artist :artist/name ?artist-name]
                   [?release :release/artists ?artist]
                   [?release :release/name ?release-name]]
         params [artist-name]"
      (lit/map->QueryAction {:name   :binding-form
                             :params [:artist-name]
                             :query  '[:find ?release-name
                                       :in $ ?artist-name
                                       :where [?artist :artist/name ?artist-name]
                                       [?release :release/artists ?artist]
                                       [?release :release/name ?release-name]]})

      "query scalar-result
         q      [:find ?year . :in $ ?name :where [?artist :artist/name ?name] [?artist :artist/startYear ?year]]
         params [name]"
      (lit/map->QueryAction {:name   :scalar-result
                             :params [:name]
                             :query  '[:find ?year . :in $ ?name :where [?artist :artist/name ?name] [?artist :artist/startYear ?year]]})

      "query params-differ-from-lvars
         q     [:find  ?release
                :in    $ [?aname ?rname]
                :where [?artist :artist/name ?aname]
                       [?release :release/artists ?artist]
                       [?release :release/name ?rname]]
         params [artist-release-pairs]"
      (lit/map->QueryAction {:name   :params-differ-from-lvars
                             :query  '[:find  ?release
                                       :in    $ [?aname ?rname]
                                       :where [?artist :artist/name ?aname]
                                       [?release :release/artists ?artist]
                                       [?release :release/name ?rname]]
                             :params [:artist-release-pairs]})

      "transact create-user
         params     [user/email user/name user/country]
         operation  vase/assert-entity
         headers    {\"Powered-by\" \"Vase\"}
         to         transaction-result"
      (lit/map->TransactAction {:name :create-user
                                :properties [:user/email :user/name :user/country]
                                :operation  :vase/assert-entity
                                :headers    {"Powered-by" "Vase"}
                                :to         :transaction-result})

      "transact record-input
         params [an-input]"
      (lit/map->TransactAction {:name :record-input
                                :properties [:an-input]}))))
