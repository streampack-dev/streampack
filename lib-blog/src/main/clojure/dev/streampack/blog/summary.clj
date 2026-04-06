;; Heuristic sentence-based summarization for blog excerpts.
;; Input should be plain text. Returns up to max-sentences in original order.

(ns dev.streampack.blog.summary
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(def ^:private stopwords
  #{"a" "an" "and" "are" "as" "at" "be" "by" "for" "from" "has" "he" "in" "is"
    "it" "its" "of" "on" "or" "that" "the" "to" "was" "were" "will" "with"})

(defn- normalize-text
  [text]
  (-> text
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- split-sentences
  [text]
  (let [breaker (java.text.BreakIterator/getSentenceInstance java.util.Locale/US)]
    (.setText breaker text)
    (loop [start (.first breaker)
           end (.next breaker)
           out []]
      (if (= end java.text.BreakIterator/DONE)
        out
        (let [sentence (-> (.substring text start end) str/trim)]
          (recur end
                 (.next breaker)
                 (if (str/blank? sentence) out (conj out sentence))))))))

(defn- tokenize
  [sentence]
  (->> (re-seq #"[A-Za-z0-9][A-Za-z0-9'\-]*" (str/lower-case sentence))
       (remove stopwords)
       (remove #(<= (count %) 2))))

(defn- token-frequencies
  [tokens]
  (clojure.core/frequencies tokens))

(defn- sentence-score
  [idx total sentence token-freq]
  (let [tokens (tokenize sentence)
        token-score (if (seq tokens)
                      (/ (reduce + 0.0 (map #(double (get token-freq % 0)) tokens))
                         (double (count tokens)))
                      0.0)
        ;; Prefer earlier sentences, but decay smoothly so mid-article points can still win.
        position-bonus (max -0.8 (- 2.5 (* 0.12 idx)))
        length-penalty (+ (if (> (count sentence) 220) 1.5 0.0)
                          (if (> (count sentence) 320) 2.0 0.0))
        short-penalty (cond
                        (< (count sentence) 28) 2.5
                        (< (count sentence) 45) 1.2
                        :else 0.0)
        parenthetical-penalty (if (re-find #"^\([^)]{1,120}\)\.?$" sentence) 3.0 0.0)
        meta-penalty (if (re-find #"(?i)\b(contributed by|this article, by the way|here we are)\b" sentence)
                       2.0
                       0.0)]
    (- (+ token-score position-bonus)
       length-penalty
       short-penalty
       parenthetical-penalty
       meta-penalty)))

(defn- jaccard
  [a b]
  (let [a (set a)
        b (set b)
        union-count (count (set/union a b))]
    (if (zero? union-count)
      0.0
      (/ (double (count (set/intersection a b)))
         (double union-count)))))

(defn- max-similarity
  [cand selected]
  (if (empty? selected)
    0.0
    (apply max
           (map (fn [sel]
                  (jaccard (:token-set cand) (:token-set sel)))
                selected))))

(defn- select-diverse
  [scored max-sentences]
  (loop [selected []
         remaining (vec scored)]
    (if (or (>= (count selected) max-sentences) (empty? remaining))
      selected
      (let [best (apply max-key
                        (fn [cand]
                          ;; MMR-like score to reduce redundancy across selected sentences.
                          (- (:score cand)
                             (* 1.4 (max-similarity cand selected))))
                        remaining)]
        (recur (conj selected best)
               (->> remaining
                    (remove #(= (:idx %) (:idx best)))
                    vec))))))

(defn summarize
  [text max-sentences]
  (let [normalized (normalize-text (or text ""))]
    (if (str/blank? normalized)
      ""
      (let [sentences (split-sentences normalized)]
        (cond
          (empty? sentences) ""
          (<= (count sentences) max-sentences) (str/join " " sentences)
          :else
          (let [candidate-count (min (count sentences) 24)
                candidate-sentences (vec (take candidate-count sentences))
                all-tokens (mapcat tokenize candidate-sentences)
                token-freq (token-frequencies all-tokens)
                scored (map-indexed
                         (fn [idx sentence]
                           (let [toks (tokenize sentence)]
                             {:idx idx
                             :sentence sentence
                             :token-set (set toks)
                             :score (sentence-score idx candidate-count sentence token-freq)}))
                         candidate-sentences)
                selected (->> (select-diverse scored max-sentences)
                              (sort-by :idx))
                selected-idx (set (map :idx selected))]
            (->> (map-indexed vector candidate-sentences)
                 (filter (fn [[idx _sentence]] (contains? selected-idx idx)))
                 (map second)
                 (str/join " "))))))))
