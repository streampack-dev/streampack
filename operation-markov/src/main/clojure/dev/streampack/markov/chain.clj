;; Markov chain text generation from a corpus of messages.
;; Pure data transformation: takes a list of strings, returns a generated sentence.

(ns dev.streampack.markov.chain
  (:require [clojure.string :as str]))

(defn- tokenize
  "Splits a message into lowercase word tokens, discarding blanks."
  [text]
  (-> text str/lower-case str/trim (str/split #"\s+") (->> (remove str/blank?))))

(defn- trigrams-from
  "Extracts trigram transitions from a single sequence of words."
  [words]
  (partition 3 1 words))

(defn- build-chain
  "Builds a trigram transition map from a sequence of messages.
   Each message is tokenized independently so transitions never cross message boundaries.
   Keys are [w1 w2] pairs, values are vectors of possible next words."
  [token-seqs]
  (->> token-seqs
       (mapcat trigrams-from)
       (reduce (fn [chain [w1 w2 w3]]
                 (update chain [w1 w2] (fnil conj []) w3))
               {})))

(defn- walk-chain
  "Walks the trigram chain from a random starting pair, producing up to max-words tokens."
  [chain max-words]
  (let [start (rand-nth (vec (keys chain)))
        [w1 w2] start]
    (loop [prev w1
           curr w2
           result [w1 w2]
           n 2]
      (if (or (>= n max-words)
              (nil? (get chain [prev curr])))
        (str/join " " result)
        (let [next-word (rand-nth (get chain [prev curr]))]
          (recur curr next-word (conj result next-word) (inc n)))))))

(defn generate
  "Generates a sentence from a list of message strings.
   Each message is tokenized independently to preserve message boundaries.
   Returns nil if the corpus is too small to build a chain."
  [messages max-words]
  (let [token-seqs (map tokenize messages)
        chain (build-chain token-seqs)]
    (when (seq chain)
      (walk-chain chain max-words))))
