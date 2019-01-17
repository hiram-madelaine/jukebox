(ns fundingcircle.juke.backend.cucumber
  "Cucumber backend for juke."
  (:gen-class
   :name cucumber.runtime.JukeCucumberRuntimeBackend
   :constructors {[cucumber.runtime.io.ResourceLoader io.cucumber.stepexpression.TypeRegistry] []}
   :init init
   :implements [cucumber.runtime.Backend])
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [fundingcircle.juke :as juke :refer [JukeBackend]])
  (:import [cucumber.runtime StepDefinition TagPredicate]
           [cucumber.runtime.snippets Concatenator FunctionNameGenerator Snippet SnippetGenerator]
           io.cucumber.cucumberexpressions.ParameterTypeRegistry
           [io.cucumber.stepexpression ExpressionArgumentMatcher StepExpressionFactory TypeRegistry]
           java.util.Locale))

(def world
  "Used to track and provide state between steps."
  (atom nil))

(defonce definitions
  (atom {:glue nil :steps [] :before [] :after [] :before-step [] :after-step []}))

(defn update-world
  "Checks whether the `world` object appears to have been dropped, and
  prints an error if so."
  [f]
  (fn [world & args]
    (let [new-world (apply f world args)]
      (when-not (::world? new-world)
        (log/errorf "The scenario step context appears to have been dropped. (Step implementations are expected to return an updated context.)"))
      new-world)))


(defn- location
  "Returns a string repr of the file and line number of the var."
  [v]
  (let [{:keys [file line]} (meta v)]
    (str file ":" line)))

(deftype JukeStepDefinition [pattern step-fn step-meta]
  cucumber.runtime.StepDefinition
  (matchedArguments [_ step]
    (.argumentsFrom
     (ExpressionArgumentMatcher.
      (.createExpression (StepExpressionFactory. (TypeRegistry. (Locale/getDefault)))
                         pattern)) step))

  (getLocation [_ detail]
    (location step-fn))

  (getParameterCount [_] nil)

  (execute [_ locale args]
    ;; call before-steps
    (doseq [{:keys [hook-fn]} (:before-step @definitions)]
      (swap! world (update-world (fn [world] (hook-fn world)))))

    ;; call step
    (try
      (swap! world (update-world (fn [world] (apply step-fn
                                                    (assoc world :scene/step step-meta)
                                                    args))))
      (catch Throwable e
        (swap! world assoc :scene/exception e)))

    ;; call after-steps
    (doseq [{:keys [hook-fn]} (:after-step @definitions)]
      (swap! world (update-world (fn [world] (hook-fn world)))))

    (when-let [e (:scene/exception @world)]
      (throw e)))

  (isDefinedAt [_ stack-trace-element]
    (let [{:keys [file line]} (meta step-fn)]
      (and (= (.getLineNumber stack-trace-element) line)
           (= (.getFileName stack-trace-element) file))))

  (getPattern [_] (str pattern))
  (isScenarioScoped [_] false))

(deftype JukeHookDefinition [tag-predicate hook-fn]
  cucumber.runtime.HookDefinition
  (getLocation [_ detail?]
    (location hook-fn))

  (execute [hd scenario]
    (swap! world (update-world
                  (fn [world]
                    (hook-fn world {:status (.getStatus scenario)
                                    :failed? (.isFailed scenario)
                                    :name (.getName scenario)
                                    :id (.getId scenario)
                                    :uri (.getUri scenario)
                                    :lines (.getLines scenario)})))))
  (matches [hd tags]
    (.apply tag-predicate tags))

  (getOrder [hd] 0)

  (isScenarioScoped [hd] false))

(defn- add-step
  "Adds a step to glue. If glue is nil, queues it up to be added later."
  [{:keys [glue] :as definitions} pattern step-fn]
  (log/debugf "Adding step: %s" [glue pattern])
  (if glue
    (do
      (.addStepDefinition glue (->JukeStepDefinition pattern step-fn (meta step-fn)))
      definitions)
    (update definitions :steps conj {:pattern pattern :step-fn step-fn})))

(defn- add-before-scene-hook
  "Adds a :scene/before hook to glue. If glue is nil, queues it up to be added later."
  [{:keys [glue] :as definitions} tags hook-fn]
  (if glue
    (do
      (.addBeforeHook glue (->JukeHookDefinition (TagPredicate. tags) hook-fn))
      definitions)
    (update definitions :before conj {:tags tags :hook-fn hook-fn})))

(defn- add-after-scene-hook
  "Adds a :scene/after hook to glue. If glue is nil, queues it up to be added later."
  [{:keys [glue] :as definitions} tags hook-fn]
  (if glue
    (do
      (.addAfterHook glue (->JukeHookDefinition (TagPredicate. tags) hook-fn))
      definitions)
    (update definitions :after conj {:tags tags :hook-fn hook-fn})))

(defn- add-before-step-hook
  "Adds a :scene/before-step hook to glue. If glue is nil, queues it up to be added later."
  [definitions hook-fn]
  (update definitions :before-step conj {:hook-fn hook-fn}))

(defn- add-after-step-hook
  "Adds a :scene/after-step hook to glue. If glue is nil, queues it up to be added later."
  [definitions hook-fn]
  (update definitions :after-step conj {:hook-fn hook-fn}))

(deftype CucumberJukeBackend []
  JukeBackend
  (register-step [_ pattern step-fn]
    (swap! definitions add-step pattern step-fn))

  (register-before-scene-hook [_ tags hook-fn]
    (swap! definitions add-before-scene-hook tags hook-fn))

  (register-after-scene-hook [_ tags hook-fn]
    (swap! definitions add-after-scene-hook tags hook-fn))

  (register-before-step-hook [_ hook-fn]
    (swap! definitions add-before-step-hook hook-fn))

  (register-after-step-hook [_ hook-fn]
    (swap! definitions add-after-step-hook hook-fn)))

(def juke-backend
  "A juke cucumber backend."
  (->CucumberJukeBackend))

(defn- set-glue
  "Sets the cucumber glue instance. Registers any steps, before hooks
  and after hooks that were queued before glue was initialized."
  [{:keys [before-step after-step] :as definitions} glue]
  (let [{:keys [steps before after] :as definitions} (assoc definitions :glue glue)]
    (doseq [{:keys [pattern step-fn]} steps]
      (add-step definitions pattern step-fn))
    (doseq [{:keys [tags hook-fn]} before]
      (add-before-scene-hook definitions tags hook-fn))
    (doseq [{:keys [tags hook-fn]} after]
      (add-after-scene-hook definitions tags hook-fn)))
  {:glue glue :steps [] :before [] :after [] :before-step before-step :after-step after-step})

(deftype ClojureFnName []
  cucumber.runtime.snippets.Concatenator
  (concatenate [_ words]
    (str/lower-case
     (str/join "-" words))))

(deftype JukeCucumberSnippet [step]
  cucumber.runtime.snippets.Snippet
  ;; From the javadoc for Snippet (https://github.com/cucumber/cucumber-jvm/blob/v3.0.2/core/src/main/java/cucumber/runtime/snippets/Snippet.java):
  ;; /**
  ;; * @return a {@link java.text.MessageFormat} template used to generate a snippet. The template can access the
  ;; * following variables:
  ;; * <p/>
  ;; * <ul>
  ;; * <li>{0} : Step Keyword</li>
  ;; * <li>{1} : Value of {@link #escapePattern(String)}</li>
  ;; * <li>{2} : Function name</li>
  ;; * <li>{3} : Value of {@link #arguments(Map)}</li>
  ;; * <li>{4} : Regexp hint comment</li>
  ;; * <li>{5} : value of {@link #tableHint()} if the step has a table</li>
  ;; * </ul>
  ;; */
  (template [_]
    (str
     "(defn {2}\n"
     "  \"Returns an updated context (`board`).\"\n"
     "  '{':scene/step \"{1}\"'}'\n"
     "  [{3}]\n"
     "  ;; {4}\n"
     "  (throw (cucumber.api.PendingException.)))\n"))
  (tableHint [_] nil)
  (arguments [_ args]
    (str/join " " (cons "board" (keys args))))
  (escapePattern [_ pattern]
    (str/replace (str pattern) "\"" "\\\"")))

(defn- -init
  [resource-loader type-registry]
  [[] nil])

(defn- -loadGlue [this glue glue-paths]
  (log/debugf "Glue paths: %s" glue-paths)
  (if (= 0 (count glue-paths))
    (juke/register juke-backend (juke/hooks))
    (doseq [glue-path glue-paths]
      (juke/register juke-backend (juke/hooks glue-path))))
  (swap! definitions set-glue glue))

(defn- -buildWorld [_]
  (reset! world {::world? true}))

(defn- -disposeWorld [_]
  (reset! world {::world? true}))

(defn- -getSnippet
  [_ step keyword _]
  (.getSnippet (SnippetGenerator. (->JukeCucumberSnippet step)
                                  (ParameterTypeRegistry. (Locale/getDefault)))
               step
               keyword
               (FunctionNameGenerator. (->ClojureFnName))))