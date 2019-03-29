(ns utils.docs
  (:require [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :refer [input-stream-push-back-reader]]
            [clojure.java.io :as io]
            [clojure.set :refer [union]]
            [clojure.string :as string]
            [quil.helpers.docs :refer [link-to-processing-reference]])
  (:import (com.vladsch.flexmark.parser Parser)
           (com.vladsch.flexmark.html HtmlRenderer LinkResolverFactory LinkResolver)
           (com.vladsch.flexmark.html.renderer ResolvedLink LinkType LinkStatus LinkResolverContext)
           (com.vladsch.flexmark.ext.gfm.tables TablesExtension)
           (com.vladsch.flexmark.ext.autolink AutolinkExtension)
           (com.vladsch.flexmark.ext.anchorlink AnchorLinkExtension)
           (com.vladsch.flexmark.ext.wikilink WikiLinkExtension)
           (com.vladsch.flexmark.util.options MutableDataSet)))

(def md-extensions
  [(TablesExtension/create)
   (AutolinkExtension/create)
   (AnchorLinkExtension/create)
   (WikiLinkExtension/create)])

(def md-container
  (.. (Parser/builder)
      (extensions md-extensions)
      (build)))

(defn- md-renderer
  "Create a Markdown renderer."
  []
  (.. (HtmlRenderer/builder
       (doto (MutableDataSet.)
         (.set AnchorLinkExtension/ANCHORLINKS_ANCHOR_CLASS "md-anchor")
         (.set HtmlRenderer/FENCED_CODE_NO_LANGUAGE_CLASS "language-clojure")))
      (linkResolverFactory
        (reify LinkResolverFactory
          (getAfterDependents [_this] nil)
          (getBeforeDependents [_this] nil)
          (affectsGlobalScope [_this] false)
          (^LinkResolver create [_this ^LinkResolverContext _ctx]
            (reify LinkResolver
              (resolveLink [_this _node _ctx link]
                (if (= (.getLinkType link) WikiLinkExtension/WIKI_LINK)
                  (ResolvedLink. LinkType/LINK
                                 (.getUrl link)
                                 nil
                                 LinkStatus/UNCHECKED)
                  link))))))
      (extensions md-extensions)
      (build)))

(defn markdown-to-html
  "Parse the given string as Markdown and return HTML."
  [markdown]
  (->> (.parse md-container markdown)
       (.render (md-renderer))))

(defn read-all [file feature]
  (let [reader (->> file
                    io/resource
                    io/input-stream
                    input-stream-push-back-reader)
        endof (gensym)]
    (->> #(rdr/read {:eof endof :read-cond :allow :features #{feature}} reader)
         (repeatedly)
         (take-while #(not= % endof))
         (doall))))

(defn get-public-fns [all-forms]
  (filter #(-> % second meta (contains? :category))
          all-forms))

(defn get-full-meta [[type name docstring args :as form]]
  (let [mt (meta name)
        args (if (vector? args)
               #{args}
               (set (mapv first (drop 3 form))))]
    (assoc mt
           :args args
           :name name
           :docstring (markdown-to-html docstring)
           :what (if (= 'defmacro type) :macro :fn)
           :link (link-to-processing-reference mt))))

(defn get-metas [forms type]
  (->> forms
       get-public-fns
       (map get-full-meta)
       (map #(assoc % :type type))
       (map #(vector (:name %) %))
       (into {})))

(defn merge-metas [meta1 meta2]
  (let [args1 (:args meta1)
        args2 (:args meta2)
        merged-args (for [arg (union args1 args2)]
                      {:value arg
                       :type (cond (and (args1 arg) (args2 arg)) :both
                                   (args1 arg) (:type meta1)
                                   (args2 arg) (:type meta2))})]
    (-> (merge meta1 meta2)
        (assoc :args merged-args
               :type :both))))

(defn merge-all-metas [metas1 metas2]
  (merge-with merge-metas metas1 metas2))

(def files ["quil/core.cljc" "quil/middleware.cljc"])

(defn generate-metas-to-file [file]
  (let [clj (-> (mapcat #(read-all % :clj) files)
                (get-metas :clj))
        cljs (-> (mapcat #(read-all % :cljs) files)
                 (get-metas :cljs))
        merged (merge-all-metas clj cljs)]
    (with-open [wtr (io/writer file)]
      (clojure.pprint/pprint merged wtr))))

(generate-metas-to-file "out.clj")

