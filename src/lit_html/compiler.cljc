(ns lit-html.compiler
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
         (merge (select-keys tag-spec [:id ::tag-classes])
                attrs)
         contents])

      ; Otherwise, no attrs were provided, so attrs is really the first
      ; contents.
      [(:tag tag-spec)
       (select-keys tag-spec [:id ::tag-classes])
       (cons attrs contents)])))

(defn- compile-classes
  "Class attributes get special handling. If the value is seqable it gets joined
  with spaces. If it's a map, it gets passed to the classMap directive."
  [v]
  (cond
    (map? v)     `(lit-html.lib.directive/classMap v)
    (seqable? v) (string/join " " (seq v))
    :else (str v)))

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
  (if (or (= "class" k)
          (= :class  k))
    (compile-classes v)
    (let [key (if (string? k) k (keyword-attr k))]
      (if (string? v)
        [(str key "=\"" v "\"")]
        [(str key "=") v]))))

(defn- compile-attrs
  [attrs]
  (mapcat compile-attr attrs))

(declare compile-html)

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

(defn- collapse-strings
  [forms]
  (loop [[a b & tail] forms
         out          []]
    (cond
      (nil? a)                      out
      (and (string? a) (nil? b))    (conj out a)
      (and (string? a) (string? b)) (recur (cons (str a b) tail) out)
      (string? b)                   (recur (cons b tail) (conj out a))
      :else                         (recur tail (conj out a b)))))

(defn- compile-html
  [element]
  (cond
    (string? element) [element]
    (symbol? element) [element]
    (list?   element) [element]

    (vector? element)
    (collapse-strings (compile-element (normalize-element element)))

    :else
    (throw (error (str "Dont know what to do with " element)))))

(def ^{:doc "It's vital to the runtime performance of lit-html that the array
            of strings passed to it for new instances of the same template be
            pointer-identical. Therefore, the fixed string portions of each
            template are extracted and cache here."}
  html-strings-cache
  (atom {}))

(defmacro html
  "Core macro for defining lit-html templates.
  Takes a Hiccup-style [:div {:style \"color: red\"} \"contents\"]
  vectors, and emits a function that will build a TemplateResult at runtime.

  Note that the strings portion is separated and cached in html-strings-cache
  so that the identical array of strings is used each time the template is
  rendered with different data. These values are added to the cache lazily."
  [element]
  (cond
    (not (vector? element))
    (throw (error "You must pass a vector to (html ...)"))

    (not (keyword? (first element)))
    (throw
      (error "The first value in an (html ...) vector must be a keyword"))

    :else
    (let [forms   (compile-html element)
          sym     (keyword (gensym "lit-html-template-strings"))
          strings (filter string? forms)
          values  (remove string? forms)]
      `(let [strs# (or (get @html-strings-cache ~sym)
                       (let [ss# (into (cljs.core/array) [~@strings])]
                         (swap! html-strings-cache assoc ~sym ss#)
                         ss#))]
         (lit-html.runtime/template-result
           strs#
           (into (cljs.core/array) [~@values]))))))

