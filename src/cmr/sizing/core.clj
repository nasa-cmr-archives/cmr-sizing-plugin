(ns cmr.sizing.core
  (:require
    [clojure.string :as string]
    [cmr.exchange.common.results.core :as results]
    [cmr.exchange.common.results.errors :as errors]
    [cmr.exchange.common.results.warnings :as warnings]
    [cmr.exchange.common.util :as util]
    [cmr.exchange.query.core :as query]
    [cmr.metadata.proxy.concepts.core :as concept]
    [cmr.metadata.proxy.concepts.granule :as granule]
    [cmr.metadata.proxy.concepts.variable :as variable]
    [cmr.metadata.proxy.results.errors :as metadata-errors]
    [cmr.ous.common :as ous-common]
    [cmr.ous.components.config :as config]
    [cmr.ous.impl.v2-1 :as ous]
    [cmr.sizing.formats :as formats]
    [cmr.sizing.spatial :as spatial]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;   Support & Utility Functions   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; XXX This function is nearly identical to one of the same name in
;;     cmr.ous.common -- we should put this somewhere both can use,
;;     after generalizing to take a func and the func's args ...
(defn process-results
  ([results start errs]
   (process-results results start errs {:warnings nil}))
  ([results start errs warns]
   (log/trace "Got data-files:" (vec (:data-files results)))
   (log/trace "Process-results tag-data:" (:tag-data results))
   (if errs
     (do
       (log/error errs)
       errs)
     (let [sample-granule-metadata-size (count (.getBytes (:granule-metadata results)))
           format-estimate (formats/estimate-size
                            (:format results)
                            (count (:data-files results))
                            (:vars results)
                            sample-granule-metadata-size
                            (:params results))]
       ;; Error handling for post-stages processing
       (if-let [errs (errors/erred? format-estimate)]
         (do
           (log/error errs)
           errs)
         (let [spatial-estimate (spatial/estimate-size
                                 format-estimate
                                 results)]
           (if-let [errs (errors/erred? spatial-estimate)]
             (do
               (log/error errs)
               errs)
             (let [estimate spatial-estimate]
               (log/debug "Generated estimate:" estimate)
               (results/create [{:bytes estimate
                                 :mb (/ estimate (Math/pow 2 20))
                                 :gb (/ estimate (Math/pow 2 30))}]
                               :elapsed (util/timed start)
                               :warnings warns)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;   API   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn estimate-size
  [component user-token raw-params]
  (let [start (util/now)
        search-endpoint (config/get-search-url component)
        ;; Stage 1
        [params bounding-box grans-promise coll-promise s1-errs]
        (ous-common/stage1
         component
         {:endpoint search-endpoint
          :token user-token
          :params raw-params})
        ;; Stage 2
        [params coll data-files service-ids vars s2-errs]
        (ous/stage2
         component
         coll-promise
         grans-promise
         {:endpoint search-endpoint
          :token user-token
          :params params})
        ;; Stage 3
        [services vars params bounding-info s3-errs s3-warns]
        (ous/stage3
         component
         service-ids
         vars
         bounding-box
         {:endpoint search-endpoint
          :token user-token
          :params params})
        warns s3-warns
        ;; XXX Note that this next `let` assignment will only work when
        ;;     supporting explicit granule concept ids passed in the request;
        ;;     as soon as we're supporting implicit granule concept ids in SES
        ;;     (i.e., granule concept ids obtained from the collection), this
        ;;     will break.
        sample-granule-id (first (:granules params))
        granule-metadata (concept/get-metadata
                          search-endpoint user-token
                          (assoc params :concept-id sample-granule-id))
        ;; Error handling for all stages
        errs (errors/collect
              params bounding-box grans-promise coll-promise s1-errs
              data-files service-ids vars s2-errs s3-errs
              granule-metadata
              {:errors (errors/check
                        [not data-files metadata-errors/empty-gnl-data-files])})

        params (assoc params :total-granule-input-bytes (:total-granule-input-bytes raw-params))
        fmt (:format params)]
    (log/trace "raw-params:" raw-params)
    (log/debug "Got format:" fmt)
    (log/debug "Got data-files:" (vec data-files))
    (log/debug "Got services:" services)
    (log/debug "Got vars:" vars)
    (log/debug "Got total-granule-input-bytes:" (:total-granule-input-bytes raw-params))
    (process-results
      {:params params
       :data-files data-files
       :vars vars
       :format fmt
       :collection-metadata coll
       :granule-metadata granule-metadata
       :bounding-info bounding-info}
      start
      errs
      warns)))
