(ns etaoin.api-with-driver-test
  "Make sure all this sugar works and is linted by clj-kondo appropriately.

  These tests are a bit wordy and code is repeated, but it all seems appropriate to me at this time.

  These tests are separate etaoin.api-test because it uses a fixture as part of its strategy.
  We do reuse the driver selection mechanism from etaoin.api-test tho."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [etaoin.api :as e]
            [etaoin.api2 :as e2]
            [etaoin.api-test :as api-test]
            [etaoin.test-report]
            [etaoin.impl.util :as util]))

(defn- match-count [re s]
  (let [matcher (re-matcher re s)]
    (loop [n 0]
      (if (re-find matcher)
        (recur (inc n))
        n))))

(defn- fake-driver-path []
  (str (fs/which "bb") " fake-driver"))

(defn testing-driver? [type]
  (some #{type} api-test/drivers))

(def my-agent "my agent")

(use-fixtures
  :once
  api-test/test-server)

(deftest capabilities-population-test
  ;; different browsers support different features and express configuration differently
  ;; a bit brittle, adjust test expectations accordingly when you make changes to capabilities
  (doseq [type api-test/drivers
          :let [capabilities (atom nil)]]
    (testing type
      (let [expected-proxy {:proxyType "manual",
                            :httpProxy "some.http.proxy.com:8080",
                            :sslProxy "some.ssl.proxy.com:8080",
                            :ftpProxy "some.ftp.proxy.com:8080",
                            :socksProxy "socksproxy:1080",
                            :socksVersion 5,
                            :noProxy ["http://this.url" "http://that.url"]}]
        (e/with-driver type {:webdriver-failed-launch-retries 0
                             :path-driver (fake-driver-path)
                             :load-strategy :none
                             :path-browser "custom-browser-bin"
                             :args ["--extra" "--args"]
                             :log-level :info
                             :profile "some/profile/dir"
                             :size [1122 771]
                             :url "https://initial-url"
                             :download-dir "some/download/dir"
                             :proxy {:http "some.http.proxy.com:8080"
                                     :ftp "some.ftp.proxy.com:8080"
                                     :ssl "some.ssl.proxy.com:8080"
                                     :socks {:host "socksproxy:1080" :version 5}
                                     :bypass ["http://this.url" "http://that.url"]}} driver
          (reset! capabilities (:capabilities driver)))
        (case type
          :chrome (is (= {:pageLoadStrategy :none
                          :proxy expected-proxy
                          :goog:loggingPrefs {:browser "INFO"},
                          :goog:chromeOptions {:w3c true
                                               :binary "custom-browser-bin"
                                               :args ["--window-size=1122,771"
                                                      "--extra" "--args"
                                                      (if (fs/windows?)
                                                        "--user-data-dir=some\\profile"
                                                        "--user-data-dir=some/profile")
                                                      "--profile-directory=dir"]
                                               :prefs {:download.default_directory
                                                       (if (fs/windows?)
                                                         "some\\download\\dir\\"
                                                         "some/download/dir/")
                                                       :download.prompt_for_download false}}}
                         @capabilities))
          :edge (is (= {:pageLoadStrategy :none
                        :proxy expected-proxy
                        :goog:loggingPrefs {:browser "INFO"},
                        :ms:edgeOptions {:w3c true
                                         :binary "custom-browser-bin"
                                         :args ["--window-size=1122,771"
                                                "--extra" "--args"]
                                         :prefs {:download.default_directory (if (fs/windows?)
                                                                               "some\\download\\dir\\"
                                                                               "some/download/dir/")
                                                 :download.prompt_for_download false}}}
                       @capabilities))
          :firefox (let [save-to-disk (get-in @capabilities [:moz:firefoxOptions :prefs :browser.helperApps.neverAsk.saveToDisk])
                         capabilities (update-in @capabilities [:moz:firefoxOptions :prefs] dissoc :browser.helperApps.neverAsk.saveToDisk)]
                     (is (= {:pageLoadStrategy :none
                             :proxy expected-proxy
                             :moz:firefoxOptions {:binary "custom-browser-bin"
                                                  :args ["-width" "1122" "-height" "771"
                                                         "--new-window" "https://initial-url"
                                                         "--extra" "--args"
                                                         "-profile" (if (fs/windows?)
                                                                          "some\\profile\\dir"
                                                                          "some/profile/dir")]
                                                  :prefs {:browser.download.dir "some/download/dir"
                                                          :browser.download.folderList 2
                                                          :browser.download.useDownloadDir true}}}
                            capabilities))
                     (is (not (str/blank? save-to-disk))))
          :safari (is (= {:pageLoadStrategy :none
                          :proxy expected-proxy
                          :safari:options {:binary "custom-browser-bin"
                                           :args ["--extra" "--args"]}}
                         @capabilities)))))))

(deftest with-driver-tests
  (let [test-page (api-test/test-server-url "test.html")]
    (when (testing-driver? :chrome)
      (testing "chrome"
        ;; with opts
        (is (= my-agent
               (e/with-driver :chrome {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-chrome {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-chrome-headless {:user-agent my-agent} driver
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))
               (e2/with-chrome [driver {:user-agent my-agent}]
                 (e/get-user-agent driver))
               (e2/with-chrome-headless [driver {:user-agent my-agent}]
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))))
        ;; without opts
        (is (= "Webdriver Test Document"
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-driver :chrome nil driver
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-driver :chrome {} driver
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-driver :chrome driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-chrome nil driver
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-chrome {} driver
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-chrome driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-chrome-headless nil driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-chrome-headless {} driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-chrome-headless driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))

               (e2/with-chrome [driver]
                 (e/go driver test-page)
                 (e/get-title driver))
               (e2/with-chrome-headless [driver]
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))))))

    (when (testing-driver? :firefox)
      (testing "firefox"
        ;; with opts
        (is (= my-agent
               (e/with-driver :firefox {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-firefox {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-firefox-headless {:user-agent my-agent} driver
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))
               (e2/with-firefox [driver {:user-agent my-agent}]
                 (e/get-user-agent driver))
               (e2/with-firefox-headless [driver {:user-agent my-agent}]
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))))
        ;; without opts
        (is (= "Webdriver Test Document"
               (e/with-driver :firefox driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-firefox nil driver
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-firefox {} driver
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-firefox driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-firefox-headless nil driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-firefox-headless {} driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-firefox-headless driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))

               (e2/with-firefox [driver]
                 (e/go driver test-page)
                 (e/get-title driver))
               (e2/with-firefox-headless [driver]
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))))))

    (when (testing-driver? :edge)
      (testing "edge"
        ;; with opts
        (is (= my-agent
               (e/with-driver :edge {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-edge {:user-agent my-agent} driver
                 (e/get-user-agent driver))
               (e/with-edge-headless {:user-agent my-agent} driver
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))
               (e2/with-edge [driver {:user-agent my-agent}]
                 (e/get-user-agent driver))
               (e2/with-edge-headless [driver {:user-agent my-agent}]
                 (is (= true (e/headless? driver)))
                 (e/get-user-agent driver))))
        ;; without opts
        (is (= "Webdriver Test Document"
               (e/with-driver :edge driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-edge nil driver
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-edge {} driver
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-edge driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-edge-headless nil driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-edge-headless {} driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-edge-headless driver
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))

               (e2/with-edge [driver]
                 (e/go driver test-page)
                 (e/get-title driver))
               (e2/with-edge-headless [driver]
                 (is (= true (e/headless? driver)))
                 (e/go driver test-page)
                 (e/get-title driver))))))

    (when (testing-driver? :safari)
      (testing "safari"
        ;; with opts
        ;; safari driver does supports neither user agent nor headless
        ;; not sure what other safari option is reflected in session... port?
        (let [port 9995]
          (e/with-driver :safari {:port port} driver
            (is (= port (:port driver)))
            (is (= true (e/running? driver))))
          (e/with-safari {:port port} driver
            (is (= port (:port driver)))
            (is (= true (e/running? driver))) )
          (e2/with-safari [driver {:port port}]
            (is (= port (:port driver)))
            (is (= true (e/running? driver)))))
        ;; without opts
        (is (= "Webdriver Test Document"
               (e/with-driver :safari driver
                 (e/go driver test-page)
                 (e/get-title driver))

               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-safari nil driver
                 (e/go driver test-page)
                 (e/get-title driver))
               #_{:clj-kondo/ignore [:etaoin/empty-opts]}
               (e/with-safari {} driver
                 (e/go driver test-page)
                 (e/get-title driver))
               (e/with-safari driver
                 (e/go driver test-page)
                 (e/get-title driver))

               (e2/with-safari [driver]
                 (e/go driver test-page)
                 (e/get-title driver))))))))

(deftest driver-log-test
  ;; these tests check for patterns we expect to see in webdriver implementations,
  ;; these implementations might change, adjust as necessary
  (let [test-page (api-test/test-server-url "test.html")]
    (when (testing-driver? :chrome)
      (testing "chrome"
        (util/with-tmp-file "chromedriver" ".log" path
          ;; chromedriver logs to stderr
          (e/with-chrome {:driver-log-level "DEBUG" :log-stderr path} driver
            (e/go driver test-page)
            (is (re-find #"\[DEBUG\]:" (slurp path)))))))
    (when (testing-driver? :edge)
      (testing "edge"
        (util/with-tmp-file "edgedriver" ".log" path
          ;; edgedriver logs to stderr
          (e/with-edge {:driver-log-level "DEBUG" :log-stderr path} driver
            (e/go driver test-page)
            (is (re-find #"\[DEBUG\]:" (slurp path)))))))
    (when (testing-driver? :firefox)
      (testing "firefox"
        (util/with-tmp-file "firefoxdriver" ".log" path
          ;; geckodriver logs to stdout
          (e/with-firefox {:driver-log-level "debug" :log-stdout path} driver
            (e/go driver test-page)
            (is (re-find #"\tDEBUG\t" (slurp path)))))))
    (when (testing-driver? :safari)
      (testing "safari log is disovered"
        ;; safari only logs to a log file
        (e/with-safari {:driver-log-level "debug"} driver
          (is (fs/exists? (:driver-log-file driver)))
          (e/go driver test-page)
          (is (re-find #"HTTPServer:" (slurp (:driver-log-file driver))))))
      (testing "safari log can be dumped (or whatever) on driver stop"
        (let [dlf (atom nil)]
          (e/with-safari {:driver-log-level "debug"
                          :post-stop-fns [(fn [driver]
                                            (reset! dlf (:driver-log-file driver)))]} _driver)
          (is (fs/exists? @dlf))
          (is (re-find #"HTTPServer:" (slurp @dlf))))))))

(deftest driver-usage-error-test
  (when (testing-driver? :safari)
      (testing "safari - usage error"
        ;; because the safaridriver log is discovered by the port, we won't have a safaridriver log
        ;; to discover on usage error, no server webdriver server will have been started.
        (util/with-tmp-file "safaridriver-out" ".log" out-path
          (util/with-tmp-file "safaridriver-err" ".log" err-path
            (let [{:keys [exception ]}
                  (try
                    (with-redefs [log/log* (fn [& _whatever])] ;; suppress retry logging that happens automatically for safari
                      (e/with-safari {:driver-log-level "debug"
                                      :log-stderr err-path
                                      :log-stdout out-path
                                      :stream-driver-log-file true
                                      :args-driver ["--invalidarg"]} _driver))
                    ;; on usage error safaridriver currently writes to both stderr and stdout, let's check that we can capture this output
                    (catch Throwable ex
                      {:exception ex}))]
                  ;; we retry launching the driver 4 times for safari by default, but when capturing to a file, we only capture the last failure
                  (is (some? exception))
                  (is (= 1 (match-count #"Usage:.*safaridriver" (slurp out-path))))
                  (is (= 1 (match-count #"unrecognized.*invalidarg" (slurp err-path))))))))))

(deftest safari-log-discovered-on-exception-test
  ;; some exception (maybe a timeout, whatever) has been thrown while the driver is running
  (when (testing-driver? :safari)
      (testing "safari log can be dumped (or whatever) on driver stop"
        (let [dlf (atom nil)
              driver-process (atom nil)
              {:keys [exception]}
              (try
                (e/with-safari {:driver-log-level "debug"
                                :post-stop-fns [(fn [driver]
                                                  (reset! dlf (:driver-log-file driver)))]} driver
                  (reset! driver-process (:process driver))
                  (throw (ex-info "something unexpected happened" {})))
                (catch Throwable ex
                  {:exception ex}))]
          (is (some? exception))
          (is (fs/exists? @dlf))
          (is (re-find #"HTTPServer:" (slurp @dlf)))
          (is (not (p/alive? @driver-process)))))))

(deftest safari-log-discovered-after-driver-exits-unexpectedly
  ;; the driver exited unexpectedly resulting in an exception
  (when (testing-driver? :safari)
    (testing "safaridriver log is can be dumped (or whatever) driver exits unexpectedly"
      (let [dlf (atom nil)
              {:keys [exception]}
              (try
                (e/with-safari {:driver-log-level "debug"
                                :post-stop-fns [(fn [driver]
                                                  (reset! dlf (:driver-log-file driver)))]} driver
                  (p/destroy (:process driver)))
                (catch Throwable ex
                  {:exception ex}))]
        (is (some? exception))
        (is (fs/exists? @dlf))
        (is (re-find #"HTTPServer:" (slurp @dlf)))))))

(deftest driver-killed-on-failure-to-run
  (doseq [retries [0 4]]
    (testing (format "every failed run should kill driver - %d retries" retries)
      (let [stop-cnt (atom 0)
            {:keys [^Throwable exception]}
            (with-redefs [log/log* (fn [& _whatever])] ;; suppress any retry logging
              (try
                (e/with-wait-timeout 0.25 ;; timeout quickly
                  (e/with-driver :chrome
                      {:path-driver (fake-driver-path)
                       :log-stdout :inherit
                       :webdriver-failed-launch-retries retries
                       :args-driver ["--start-server" false] ;; driver process starts but server does not
                       :post-stop-fns [(fn [driver]
                                         (is (not (-> driver :process p/alive?)))
                                         (swap! stop-cnt inc))]} _driver))
                (catch Throwable ex
                  {:exception ex})))]
        (is (= :etaoin/timeout (some-> exception .getCause ex-data :type)))
        (is (some? exception))
        (is (= (inc retries) @stop-cnt))))))

(deftest driver-killed-on-exception
  (doseq [retries [0 4]]
    (testing (format "every failed run should kill driver - %d retries" retries)
      (let [stop-cnt (atom 0)
            {:keys [exception]}
            (with-redefs [log/log* (fn [& _whatever])] ;; there should be no retries
              (try
                (e/with-driver :chrome
                    {:path-driver (fake-driver-path)
                     :webdriver-failed-launch-retries retries
                     :post-stop-fns [(fn [driver]
                                       (is (not (-> driver :process p/alive?)))
                                       (swap! stop-cnt inc))]} _driver
                  (throw (ex-info "Something bad happened" {:type ::badness})))
                (catch Throwable ex
                  {:exception ex})))]
        (is (= ::badness (some-> exception ex-data :type)))
        ;; driver started fine, so no retries should have occurred
        (is (= 1 @stop-cnt))))))
