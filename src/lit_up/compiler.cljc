(ns lit-up.compiler
  "A revised and lit-html specific Hiccup-style syntax.

  - lit-html only allows bindings in text content and attribute value positions.
  - It supports a few different flavours of attribute-like binding:
    - foo=${bar} for conventional string-interpolated values
    - ?foo=${flag} for present/absent boolean attributes like 'disabled'
    - .foo=${bar} for binding to DOM element properties
    - @click=${handler} for event listeners

  To that end, we constrain standard Hiccup as follows:
  - All tags must be literal keywords.
  - The optional, map-shaped second argument giving the attributes must be a
    literal map, if present.
    - The keys of that map must be compile-time keywords or strings.
    - If a string, its first character is interpreted as above: ? . and @
    - Keyword interpolation uses the namespace:
      - :flag/disabled for the boolean ?disabled style
      - :prop/foo for the .foo DOM property style
      - :on/click for the @click event handler style
  - Classes and IDs from the tag, eg. :div#some-id.classy, gets merged with
    the attributes.
    - It's an error to specify :id in both places.
    - Classes are the union of both.

  Otherwise these values work as expected, and you can call other lit-html
  functions in the body of an element, and their TemplateResults will be used
  just like vanilla JavaScript lit-html."
  (:require
    [clojure.string :as string]))

(def ^{:doc "Regular expression that parses a CSS-style id and class from a tag name."
       :private true}
  re-tag #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

(def ^{:doc "A list of tags that need an explicit ending tag."}
  container-tags
  #{"a" "b" "body" "canvas" "dd" "div" "dl" "dt" "em" "fieldset" "form"
    "h1" "h2" "h3" "h4" "h5" "h6" "head" "html" "i" "iframe" "label" "li" "ol"
    "option" "pre" "script" "span" "string" "style" "table" "textarea" "ul"})

(defn- tag-parts
  [full-tag]
  (let [[_ tag id classes] (re-matches re-tag (name full-tag))]
    (cond-> {:tag tag}
      id      (assoc :id id)
      classes (assoc ::tag-classes (string/split classes #"\.")))))

(defn error
  [msg]
  (#?(:clj Exception. :cljs js/Error.) msg))

(defn- merge-classes
  "From-tag is always a list of strings, or nil.
  The attrs value could be nil, a space-separated string, a map, or a list
  containing strings and maps.

  The end result of this function is always a list of (separated) strings and
  maps. They'll be combined later on."
  [from-tag from-attrs]
  (let [tagged (or from-tag [])]
    (cond
      (map? from-attrs)     (conj tagged from-attrs)
      (nil? from-attrs)     (if (empty? tagged) nil tagged)
      (string? from-attrs)  (concat tagged (string/split from-attrs #" +"))
      (seqable? from-attrs) (concat tagged (seq from-attrs)))))


(defn- normalize-element
  "Given an element, possibly with [:div#fancy.tag ...] and with or without an
  attribute map, hide those messy details and return an basic tag name,
  possibly-empty attribute map, and actual contents."
  [[tag attrs & contents]]
  (let [tag-spec (tag-parts tag)]
    (if (map? attrs)
      (if (and (:id tag-spec) (:id attrs))
        (throw (error "Cannot provide both :tag#id and {:id ...}"))
        ; Otherwise, we've got real args.
        [(:tag tag-spec)
           (merge {:id (:id tag-spec)
                   :class (merge-classes (::tag-classes tag-spec) (:class attrs))}
                  (dissoc attrs :class))
           contents])

      ; Otherwise, no attrs were provided, so attrs is really the first
      ; contents.
      [(:tag tag-spec)
       {:id (:id tag-spec) :class (::tag-classes tag-spec)}
       (cons attrs contents)])))

(defn- compile-classes
  "Class attributes get special handling. The value has always been massaged to
  either a list of singular string class names or a single classMap-style map.
  We prefer a list of strings if possible, and a single class-map call if not."
  [v]
  (let [strings  (filter string? v)
        maps     (filter map?    v)]
    (if (not (empty? maps))
      (let [combined (reduce (fn [m cls] (assoc m cls true))
                             (apply merge maps)
                             strings)]
        [" class=" `(lit-up.runtime/class-map ~combined)])
      ; Easy case, no maps.
      [(str " class=\"" (string/join " " strings) "\"")])))


(defn- keyword-attr
  [k]
  (str (case (namespace k)
         "prop" "."
         "flag" "?"
         "on"   "@"
         "")
       (name k)))


(defn- compile-attr
  [[k v]]
  (cond
    (or (= "class" k)
        (= :class  k))
    (compile-classes v)

    (nil? v) [] ; Skip blank attributes
    :else (let [key (if (string? k) k (keyword-attr k))]
            (if (string? v)
              [(str " " key "=\"" v "\"")]
              [(str " " key "=") v]))))

(comment
  (compile-attrs '{:class ["foo"]
                 :on/click (fn [] holla)})
  )

(defn- compile-attrs
  [attrs]
  (mapcat compile-attr attrs))

(declare compile-templates compile-html)

(defn- compile-element
  [[tag attrs contents]]
  (if (or contents (container-tags tag))
    `[~(str "<" tag " ")
      ~@(compile-attrs attrs)
      ">"
      ~@(mapcat compile-html contents)
      ~(str "</" tag ">")]
    `[~(str "<" tag " ")
      ~@(compile-attrs attrs)
      " />"]))

(defn- clean-alternation
  "Combines runs of adjacent strings into one string, and puts empty strings
  between adjacent non-literal values."
  [forms]
  (apply concat
         (for [ch (partition-by string? forms)]
           (if (string? (first ch))
             [(string/join "" ch)]
             (interpose "" ch)))))

(comment
 (macroexpand '(html [:div.landing-page-contents
         [:h3 "22 Years of Flight Simulation Experience"]
         [:div.landing-section.flex-row.flex-justify-center
          (membership-card)
          (latest-arrivals)]
         [:h3 "Elevate your Experience"]
         (landing-blurbs)
         [:div.landing-section
          "Stats go here"
          ;(stats/new-members)
          ;(stats/monthly-pilots)
          ;(stats/recent-promotions)
          ]]))

 (macroexpand '(html [:div.foo-bar {:class {:asdf true}
                                    :on/click (fn [] holla)} "contents"]))

 (macroexpand '(html [:div.mobile-button
                      {:class {:cross expanded}
                       :on/click click}
                      [:span.line.top]
                      [:span.line.middle]
                      [:span.line.bottom]]))
 )

(defn- compile-html
  [element]
  (cond
    (string? element) [element]
    (number? element) [(str element)]
    (symbol? element) [element]

    ; Special case for (for [...] ...)
    (and (list? element)
         (= 'for (first element)))
    [`(doall (for ~(second element) ~@(map #(if (vector? %) (compile-templates [%]) %) (drop 2 element))))]

    ; Special case for (when cond ...)
    (and (list? element)
         (= 'when (first element)))
    [`(when ~(second element) ~@(map #(if (vector? %) (compile-templates [%]) %) (drop 2 element)))]

    ; Special case for (cond ...)
    (and (list? element)
         (= 'cond (first element)))
    [`(cond ~@(mapcat (fn [[p e]] [p (if (vector? e) (compile-templates [e]) e)])
                      (partition 2 (rest element))))]

    (list?   element) [element]
    (nil?    element) []

    (vector? element)
    (-> element normalize-element compile-element clean-alternation)

    :else
    (throw (error (str "Don't know what to do with " element)))))

(comment
  (compile-html '[:div (for [x xs] [:span x])])
  (macroexpand '(html [:ul (when foo [:li "butts"])]))
  )

(def ^{:doc "It's vital to the runtime performance of lit-html that the array
            of strings passed to it for new instances of the same template be
            pointer-identical. Therefore, the fixed string portions of each
            template are extracted and cache here."}
  html-strings-cache
  (atom {}))

(defn- compile-templates
  [elements]
  (let [forms   (clean-alternation (mapcat compile-html elements))
        sym     (keyword (gensym "lit-html-template-strings"))
        strings (filter string? forms)
        values  (remove string? forms)]
    `(let [strs# (or (get @html-strings-cache ~sym)
                     (let [ss# (into (cljs.core/array) [~@strings])]
                       (swap! html-strings-cache assoc ~sym ss#)
                       ss#))]
       (lit-up.runtime/template-result
         strs#
         (into (cljs.core/array) [~@values])))))

(defmacro html
  "Core macro for defining lit-html templates.
  Takes a Hiccup-style [:div {:style \"color: red\"} \"contents\"]
  vectors, and emits a function that will build a TemplateResult at runtime.

  Note that the strings portion is separated and cached in html-strings-cache
  so that the identical array of strings is used each time the template is
  rendered with different data. These values are added to the cache lazily."
  [& elements]
  (cond
    (not (every? vector? elements))
    (throw (error "You must pass vectors to (html ...)"))

    (not (every? keyword? (map first elements)))
    (throw
      (error "The first value in an (html ...) vector must be a keyword"))

    :else (compile-templates elements)))

