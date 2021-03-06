(ns metabase.query-processor.middleware.wrap-value-literals
  "Middleware that wraps value literals in `value`/`absolute-datetime`/etc. clauses containing relevant type
  information; parses datetime string literals when appropriate."
  (:require [metabase.mbql
             [predicates :as mbql.preds]
             [schema :as mbql.s]
             [util :as mbql.u]]
            [metabase.models.field :refer [Field]]
            [metabase.query-processor.store :as qp.store]
            [metabase.util.date :as du])
  (:import java.util.TimeZone))

;;; --------------------------------------------------- Type Info ----------------------------------------------------

(defmulti ^:private type-info
  "Get information about database, base, and special types for an object. This is passed to along to various
  `->honeysql` method implementations so drivers have the information they need to handle raw values like Strings,
  which may need to be parsed as a certain type."
  {:arglists '([field-clause])}
  mbql.u/dispatch-by-clause-name-or-class)

(defmethod type-info :default [_] nil)

(defmethod type-info (class Field) [this]
  (let [field-info (select-keys this [:base_type :special_type :database_type])]
    (merge
     field-info
     ;; add in a default unit for this Field so we know to wrap datetime strings in `absolute-datetime` below based on
     ;; its presence. It will get replaced by `:datetime-field` unit if we're wrapped by one
     (when (mbql.u/datetime-field? field-info)
       {:unit :default}))))

(defmethod type-info :field-id [[_ field-id]]
  (type-info (qp.store/field field-id)))

(defmethod type-info :joined-field [[_ _ field]]
  (type-info field))

(defmethod type-info :datetime-field [[_ field unit]]
  (assoc (type-info field) :unit unit))


;;; ------------------------------------------------- add-type-info --------------------------------------------------

(defmulti ^:private add-type-info
  "Wraps value literals in `:value` clauses that includes base type info about the Field they're being compared against
  for easy driver QP implementation. Temporal literals (e.g., ISO-8601 strings) get wrapped in `:time` or
  `:absolute-datetime` instead which includes unit as well; temporal strings get parsed and converted to "
  {:arglists '([x info & {:keys [parse-datetime-strings?]}])}
  (fn [x & _] (class x)))

(defmethod add-type-info nil [_ info & _]
  [:value nil info])

(defmethod add-type-info Object [this info & _]
  [:value this info])

(defmethod add-type-info java.util.Date [this info & _]
  [:absolute-datetime (du/->Timestamp this) (get info :unit :default)])

(defmethod add-type-info java.sql.Time [this info & _]
  [:time this (get info :unit :default)])

(defmethod add-type-info java.sql.Timestamp [this info & _]
  [:absolute-datetime this (get info :unit :default)])

(defn- maybe-parse-as-time [time-str unit report-timezone]
  (when (mbql.preds/TimeUnit? unit)
    (du/str->time time-str (when report-timezone
                             (TimeZone/getTimeZone ^String report-timezone)))))

(defmethod add-type-info String
  [this info & {:keys [parse-datetime-strings? report-timezone]
                :or   {parse-datetime-strings? true}}]
  (if-let [unit (when (and (du/date-string? this)
                           parse-datetime-strings?)
                  (:unit info))]
    ;; should use report timezone by default
    (if-let [time (maybe-parse-as-time this unit report-timezone)]
      [:time time unit]
      (let [timestamp (if report-timezone
                        (du/->Timestamp this report-timezone)
                        (du/->Timestamp this))]
        [:absolute-datetime timestamp unit]))
    [:value this info]))


;;; -------------------------------------------- wrap-literals-in-clause ---------------------------------------------

(def ^:private raw-value? (complement mbql.u/mbql-clause?))

(defn ^:private wrap-value-literals-in-mbql-query
  [{:keys [source-query], :as inner-query} {:keys [report-timezone], :as options}]
  (let [inner-query (cond-> inner-query
                      source-query (update :source-query wrap-value-literals-in-mbql-query options))]
    (mbql.u/replace inner-query
      [(clause :guard #{:= :!= :< :> :<= :>=}) field (x :guard raw-value?)]
      [clause field (add-type-info x (type-info field), :report-timezone report-timezone)]

      [:between field (min-val :guard raw-value?) (max-val :guard raw-value?)]
      [:between
       field
       (add-type-info min-val (type-info field), :report-timezone report-timezone)
       (add-type-info max-val (type-info field), :report-timezone report-timezone)]

      [(clause :guard #{:starts-with :ends-with :contains}) field (s :guard string?) & more]
      (let [s (add-type-info s (type-info field), :parse-datetime-strings? false)]
        (into [clause field s] more)))))

(defn- wrap-value-literals*
  [{{:keys [report-timezone]} :settings, query-type :type, :as query}]
  (if-not (= query-type :query)
    query
    (mbql.s/validate-query
     (update query :query wrap-value-literals-in-mbql-query {:report-timezone report-timezone}))))

(defn wrap-value-literals
  "Middleware that wraps ran value literals in `:value` (for integers, strings, etc.) or `:absolute-datetime` (for
  datetime strings, etc.) clauses which include info about the Field they are being compared to. This is done mostly
  to make it easier for drivers to write implementations that rely on multimethod dispatch (by clause name) -- they
  can dispatch directly off of these clauses."
  [qp]
  (comp qp wrap-value-literals*))
