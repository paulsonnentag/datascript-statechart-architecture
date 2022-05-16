(ns inspector.test-runner
  (:require
    [shadow.test :as st]
    [shadow.test.env :as env]
    [shadow.dom :as dom]
    [cljs-test-display.core :as ctd]
    [cljs-test-display.core :as display]
    [cljs.test :refer-macros [run-tests] :refer [empty-env]]
    [goog.dom.classlist :as classlist]
    [goog.dom :as gdom]
    [cljs.pprint :as pp]
    [pjstadig.print :as p]
    [pjstadig.util :as util]
    [clojure.string :as str])
  (:import [goog.string StringBuffer]))

(def dummy-env (empty-env))

;; reporting with diffs

(set! display/add-fail-node!
        (fn add-fail-node! [m]
          (let [out (binding [cljs.test/*current-env* dummy-env] ;; we don't want `humane-test-output` to modify the env
                      (with-out-str
                        (util/report- (p/convert-event m))))
                clean-out (->> (str/split out #"\n")
                               (drop-while #(not (str/starts-with? % "expected")))
                               (str/join "\n")
                               (str (with-out-str (pp/pprint (:expected m))) "\n"))
                node (display/div :test-fail
                                  (display/contexts-node)
                                  (display/div :fail-body
                                               (when-let [message (:message m)]
                                                 (display/div :test-message message))
                                               (display/n :pre {}
                                                          (display/n :code {} clean-out))))
                curr-node (display/current-node)]
            #_(classlist/add curr-node "has-failures")
            #_(classlist/add (display/current-node-parent) "has-failures")
            (gdom/appendChild curr-node node))))


(defn start []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests (ctd/init! "test-root")))

#_(defn stop [done]
    ;; FIXME: determine if async tests are still pending
    (done))

(defn ^:dev/after-load init []
  (dom/append [:div#test-root])
  (start))

