(ns uncomplicate.diamond.internal.neanderthal.rnn
  (:require [uncomplicate.commons.core :refer [let-release]]
            [uncomplicate.neanderthal
             [core :refer [view-vctr zero]]]
            [uncomplicate.diamond.tensor :refer [shape output]]
            [uncomplicate.diamond.internal.protocols :refer [diff-weights weights bias diff-input]]
            [uncomplicate.diamond.internal.neanderthal.directed :refer [->SGDLayer ->AdamLayer]]))

(defn sgd-rnn-layer [fact bluep op-bluep activ-bluep srcs prop-diff?]
  (let-release [op (op-bluep srcs prop-diff? true)
                activ (activ-bluep (output op) (diff-input op))]
    (->SGDLayer fact bluep op activ (second (shape bluep))
                (view-vctr (diff-weights op)) (view-vctr (weights op))
                (view-vctr (bias op)))))

(defn adam-rnn-layer [fact bluep op-bluep activ-bluep srcs prop-diff?]
  (let-release [op (op-bluep srcs prop-diff? false)
                activ (activ-bluep (output op) (diff-input op))
                w (view-vctr (weights op))
                s (zero w)
                r (zero w)]
    (->AdamLayer fact bluep op activ (second (shape bluep))
                 s r w (view-vctr (diff-weights op)) (view-vctr (bias op)))))
