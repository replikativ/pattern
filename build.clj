(ns build
  (:require [clojure.tools.build.api :as b]))

;; Released by replikativ as a fork of com.github.pangloss/pattern (Apache-2.0).
;; Carries the StackOverflow fix for the ?:* sequence matcher (PR'd upstream).
(def lib 'org.replikativ/pattern)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def scm-url "https://github.com/replikativ/pattern")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url scm-url
                      :connection (str "scm:git:" scm-url ".git")
                      :developerConnection (str "scm:git:" scm-url ".git")
                      :tag (str "v" version)}
                :pom-data
                [[:description "Pattern matching and term rewriting for Clojure (replikativ fork of pangloss/pattern)."]
                 [:url scm-url]
                 [:licenses
                  [:license
                   [:name "Apache-2.0"]
                   [:url "https://www.apache.org/licenses/LICENSE-2.0.txt"]
                   [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  {:jar-file jar-file :version version})

(defn install
  "Clean, generate a jar and install the jar into the local Maven repository."
  [opts]
  (clean opts)
  (let [{:keys [version jar-file]} (jar opts)]
    (b/install {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :jar-file jar-file})
    (println (str "Installed " jar-file " to local Maven repository."))
    opts))

(defn publish
  "Generates a jar with all project sources and resources and publishes it to
  Clojars."
  [opts]
  (clean opts)
  (let [{:keys [jar-file]} (jar opts)]
    (println (str "Publishing " jar-file " to Clojars!"))
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     (merge {:installer :remote
             :sign-releases? false
             :artifact jar-file
             :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
            opts))
    ;; TODO put a catch here if the version already exists?
    (println "Published.")
    opts))
