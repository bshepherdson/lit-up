# lit-html with Hiccup-like syntax

This is a ClojureScript library which wraps the
[lit-html](https://https://lit-html.polymer-project.org/) project with a
compiler for [Hiccup](https://github.com/weavejester/hiccup)-like syntax.

Alpha-quality! It works nicely in a nontrivial app, but that app is not launched
so it's hardly been battle-tested.

## Overview

This library declares an `:npm-deps` on the upstream lit-html library. That's an
alpha feature in optimized production builds, so be careful!

See [Hiccup](https://github.com/weavejester/hiccup) for the general details of
the syntax. A brief example:

```cljs
(ns my.app
  (:require
    [lit-html.compiler :refer-macros [html]]
    [lit-html.runtime :refer [render]]))

(defonce app-state (atom {:counter 0}))

(defn counter-component [counter]
  (html [:p "Counter: " [:span.red counter]]))

(defn page [state]
  (html [:div
         (counter-component (:counter state))
         [:button {:on/click (fn [_] (swap! app-state update :counter inc))}
                  "Increment"]]))
```

## Differences from Standard Hiccup

lit-html only allows interpolation in **attribute value** and **text context**
positions. Further, it has a few different attribute-like bindings, for event
handlers and DOM element properties.

- All tags must be literal keywords.
- The optional, map-shaped second argument for attributes must be a literal map,
  if present.
  - The keys of that map must be literal keywords or strings.
  - If a string, its first character is interpreted as in lit-html (see below).
  - If a keyword, the namespace is used for the different types of binding (see below).
- Classes and IDs on the tag, eg. `:div#some-id.classic.red` get merged with the
  attribute map, if any.
  - It is an error to specify the ID in both places.
  - Classes are merged together.

| Type                 | String        | Keyword          |
| --                   | --            | --               |
| Attribute            | `"foo"`       | `:foo`           |
| Boolean attribute    | `"?disabled"` | `:flag/disabled` |
| DOM element property | `".foo"`      | `:prop/foo`      |
| Event listener       | `"@click"`    | `:on/click`      |

The run-time value of an `(html ...)` call is a `TemplateResult` instance, just
like an `html` templated string in vanilla JavaScript lit-html.


## Development

To get an interactive development environment run:

    clojure -A:fig:build
    user=> (go)

This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

To clean all compiled files:

    rm -rf target/public

To create a production build run:

	rm -rf target/public
	clojure -A:fig:min


## License

Copyright © 2021 Braden Shepherdson

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
