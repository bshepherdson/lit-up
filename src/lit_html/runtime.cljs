(ns lit-html.runtime
  (:require
    ["lit-html/lib/default-template-processor" :refer [defaultTemplateProcessor]]
    ["lit-html/lib/directive" :as directive]
    ["lit-html/lib/render" :as lit]
    ["lit-html/lib/template-result" :refer [TemplateResult]]
    ))

(extend-type array
  ICollection
  (-conj [coll o]
    (.push coll o)
    coll))

(def directive directive/directive)
(def directive? directive/isDirective)

; Basic design:
; - lit-html is designed to split a template string with interpolations into the
;   fixed and variable parts.
; - hiccup syntax isn't all strings but much of it compiles to strings. The
;   macro should reduce our data structures at runtime to a stream of strings
;   and placeholder code.
;   - The runtime function should compute the current interpolated values and
;     call (TemplateResult. strings values "html" defaultTemplateProcessor)

; A quick-and-dirty hack might be a macro that wraps hiccups.core/html with one
; that replaces all non-literal values (eg. function calls, variables) with some
; placeholder string, then renders the string with hiccups and splits on the
; placeholder.
; That doesn't work for the attributes, though. So really I should build a
; hiccups clone that supports the partial rendering needed here.

(defn template-result
  "Interprets a seq of strings and other values as a template for efficient
  rendering and updating by lit-html."
  [strings values]
  (TemplateResult. strings values "html" defaultTemplateProcessor))


(defn render
  ([result container]
   (render result container nil))
  ([result container options]
   (lit/render result container options)))

