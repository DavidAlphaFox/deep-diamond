(ns uncomplicate.diamond.internal.cudnn.directed
  (:require [uncomplicate.commons
             [core :refer [Releaseable release let-release with-release Info info view]]
             [utils :refer [dragan-says-ex]]]
            [uncomplicate.fluokitten.core :refer [foldmap fmap]]
            [uncomplicate.clojurecuda.core :refer [mem-alloc]]
            [uncomplicate.neanderthal
             [core :refer [axpby! axpy! copy! transfer! raw view-vctr entry! scal!]]
             [block :refer [cast-prim data-accessor buffer]]]
            [uncomplicate.diamond
             [tensor :as tz
              :refer [Transfer input output connector revert shape layout TensorDescriptor]]]
            [uncomplicate.diamond.internal
             [protocols
              :refer [Parameters bias weights ParametersSeq parameters DescriptorProvider
                      DiamondFactoryProvider DiffParameters Backprop forward backward DiffTransfer
                      diff-input diff-output diff-z LinearBackprop backward-diff inf-desc train-desc
                      Initializable init Workspace inf-ws-size train-ws-size *workspace* create-tensor]]
             [utils :refer [transfer-weights-bias! default-strides concat-strides concat-dst-shape]]]
            [uncomplicate.diamond.internal.cudnn
             [core :refer :all]
             [protocols :refer :all]
             [tensor :refer [cudnn-tensor-desc cudnn-tensor]]]
            [uncomplicate.diamond.internal.neanderthal.directed
             :refer [->DirectedLayerBlueprint ->GaussianDropoutBlueprint]])
  (:import [clojure.lang IFn AFn]
           [uncomplicate.diamond.internal.neanderthal.directed
            InnerProductBlueprint DirectedLayerBlueprint GaussianDropoutBlueprint]))

(defn cudnn-contiguous-desc [md]
  (let [s (shape md)]
    (if (and (= :float (data-type md))
             (= (size md) (apply * Float/BYTES s)))
      (view md)
      (cudnn-tensor-desc s :float (default-strides s)))))

;; ================================ Activation =============================================

(deftype CUDnnActivationInference [cudnn-hdl bluep activation-desc a-tz one zero linear]
  Releaseable
  (release [_]
    (release a-tz))
  Info
  (info [this]
    {:activation (info bluep :activation)
     :a (info a-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    a-tz)
  (output [_]
    a-tz)
  IFn
  (invoke [_]
    (when-not linear
      (activation-forward cudnn-hdl activation-desc
                          one a-tz (buffer a-tz)
                          zero a-tz (buffer a-tz)))
    a-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(deftype CUDnnLinearActivationTraining [cudnn-hdl bluep activation-desc z-tz a-tz one zero]
  Releaseable
  (release [_]
    (release z-tz)
    (release a-tz))
  Info
  (info [this]
    {:activation (info bluep :activation)
     :z (info z-tz)
     :a (info a-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      :z (info z-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    a-tz)
  DiffTransfer
  (diff-input [_]
    a-tz)
  (diff-output [_]
    z-tz)
  IFn
  (invoke [_]
    (copy! z-tz a-tz)
    a-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs))
  Backprop
  (forward [this]
    (copy! z-tz a-tz)
    this)
  (backward [this]
    (copy! a-tz z-tz)
    this))

(deftype CUDnnActivationTraining [cudnn-hdl bluep activation-desc z-tz a-tz da-tz one zero]
  Releaseable
  (release [_]
    (release z-tz)
    (release a-tz)
    (release da-tz))
  Info
  (info [this]
    {:activation (info bluep :activation)
     :z (info z-tz)
     :a (info a-tz)
     :da (info da-tz)})
  (info [this info-type]
    (case info-type
      :a (info a-tz)
      :z (info z-tz)
      :da (info da-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    a-tz)
  DiffTransfer
  (diff-input [_]
    da-tz)
  (diff-output [_]
    z-tz)
  IFn
  (invoke [_]
    (activation-forward cudnn-hdl activation-desc
                        one z-tz (buffer z-tz) zero a-tz (buffer a-tz))
    a-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs))
  Backprop
  (forward [this]
    (activation-forward cudnn-hdl activation-desc
                        one z-tz (buffer z-tz) zero a-tz (buffer a-tz))
    this)
  (backward [this]
    (activation-backward cudnn-hdl activation-desc
                         one a-tz (buffer a-tz) da-tz (buffer da-tz) z-tz (buffer z-tz)
                         zero z-tz (buffer z-tz))
    this))

(deftype CUDnnActivationBlueprint [fact activ ad data-desc]
  Releaseable
  (release [_]
    (release data-desc)
    (release ad))
  Info
  (info [this]
    {:activation activ})
  (info [this info-type]
    (case info-type
      :activation activ
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [_]
    (view data-desc))
  (train-desc [_]
    (view data-desc))
  TensorDescriptor
  (shape [_]
    (shape data-desc))
  (data-type [_]
    (tz/data-type data-desc))
  (layout [_]
    (layout data-desc))
  IFn
  (invoke [this src-tz]
    (->CUDnnActivationInference (handle fact) this ad src-tz
                                (cast-prim (data-accessor src-tz) 1.0)
                                (cast-prim (data-accessor src-tz) 0.0)
                                (or (= :linear activ) (= :identity activ))))
  (invoke [this src-tz dst-tz]
    (cond
      (or (= :linear activ) (= :identity activ))
      (->CUDnnLinearActivationTraining (handle fact) this ad src-tz dst-tz
                                       (cast-prim (data-accessor src-tz) 1.0)
                                       (cast-prim (data-accessor dst-tz) 0.0))
      (or (= :sigmoid activ) (:logistic activ))
      (let-release [diff-tz (create-tensor fact (view dst-tz) false)]
        (->CUDnnActivationTraining (handle fact) this ad src-tz dst-tz diff-tz
                                   (cast-prim (data-accessor src-tz) 1.0)
                                   (cast-prim (data-accessor dst-tz) 0.0)))
      :default
      (->CUDnnActivationTraining (handle fact) this ad src-tz dst-tz (view dst-tz)
                                 (cast-prim (data-accessor src-tz) 1.0)
                                 (cast-prim (data-accessor dst-tz) 0.0))))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

;; ================================ Softmax =============================================

(deftype CUDnnSoftmaxInference [cudnn-hdl bluep z-tz one zero]
  Releaseable
  (release [_]
    (release z-tz))
  Info
  (info [this]
    {:activation :softmax
     :z (info z-tz)})
  (info [this info-type]
    (case info-type
      :activation :softmax
      :z (info z-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    z-tz)
  IFn
  (invoke [_]
    (softmax-forward cudnn-hdl :accurate :instance
                     one z-tz (buffer z-tz) zero z-tz (buffer z-tz))
    z-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(deftype CUDnnSoftmaxTraining [cudnn-hdl bluep z-tz da-tz one zero]
  Releaseable
  (release [_]
    (release z-tz)
    (release da-tz))
  Info
  (info [this]
    {:activation :softmax
     :z (info z-tz)
     :da (info da-tz)})
  (info [this info-type]
    (case info-type
      :z (info z-tz)
      :da (info da-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    z-tz)
  (output [_]
    z-tz)
  DiffTransfer
  (diff-input [_]
    da-tz)
  (diff-output [_]
    z-tz)
  IFn
  (invoke [_]
    (softmax-forward cudnn-hdl :accurate :instance
                     one z-tz (buffer z-tz) zero z-tz (buffer z-tz))
    z-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs))
  Backprop
  (forward [this]
    (softmax-forward cudnn-hdl :accurate :instance
                     one z-tz (buffer z-tz) zero z-tz (buffer z-tz))
    this)
  (backward [this]
    (softmax-backward cudnn-hdl :accurate :instance
                      one z-tz (buffer z-tz) da-tz (buffer da-tz)
                      zero z-tz (buffer z-tz))
    this))

(deftype CUDnnSoftmaxBlueprint [fact data-desc]
  Releaseable
  (release [_]
    (release data-desc))
  Info
  (info [this]
    {:activation :softmax})
  (info [this info-type]
    (case info-type
      :activation :softmax
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [_]
    (view data-desc))
  (train-desc [_]
    (view data-desc))
  TensorDescriptor
  (shape [_]
    (shape data-desc))
  (data-type [_]
    (tz/data-type data-desc))
  (layout [_]
    (layout data-desc))
  IFn
  (invoke [this src-tz]
    (->CUDnnSoftmaxInference (handle fact) this src-tz
                             (cast-prim (data-accessor src-tz) 1.0)
                             (cast-prim (data-accessor src-tz) 0.0)))
  (invoke [this src-tz dst-tz]
    (->CUDnnSoftmaxTraining (handle fact) this src-tz (view dst-tz)
                            (cast-prim (data-accessor src-tz) 1.0)
                            (cast-prim (data-accessor dst-tz) 0.0)))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defn cudnn-activ-blueprint [fact data-desc activ coef]
  (if (= :softmax activ)
    (->CUDnnSoftmaxBlueprint fact data-desc)
    (let-release [ad (activation-descriptor activ true coef)]
      (->CUDnnActivationBlueprint fact activ ad data-desc))))

;; ============================= Cost Function ========================================

(deftype CUDnnUniversalCost [prev-layer
                             connect-output connect-diff train-tz
                             a-y y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff)
    (release train-tz)
    (release a-y)
    (release y))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  DiffTransfer
  (diff-input [_]
    train-tz)
  (diff-output [_]
    (output connect-diff))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (axpy! -1.0 y a-y)
    (connect-diff)
    (backward prev-layer)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (axpy! -1.0 y a-y)
    (cost a-y))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defn cudnn-universal-cost [prev-layer train-tz cost]
  (let [train-desc (desc train-tz)]
    (let-release [connect-output (connector (output prev-layer) train-desc)
                  connect-diff (connector train-desc (diff-input prev-layer))]
      (->CUDnnUniversalCost prev-layer
                            connect-output connect-diff train-tz
                            (view-vctr (input connect-diff)) (view-vctr train-tz)
                            cost))))

(deftype CUDnnCustomCost [prev-layer
                          connect-output connect-diff train-tz
                          a y a-y cost]
  Releaseable
  (release [_]
    (release connect-output)
    (release connect-diff)
    (release train-tz)
    (release a)
    (release y)
    (release a-y))
  Transfer
  (input [this]
    (input connect-output))
  (output [_]
    (output connect-output))
  DiffTransfer
  (diff-input [_]
    train-tz)
  (diff-output [_]
    (output connect-diff))
  Backprop
  (forward [this]
    (connect-output)
    this)
  (backward [this]
    (copy! a a-y)
    (axpy! -1.0 y a-y)
    (connect-diff)
    this)
  IFn
  (invoke [_]
    (connect-output)
    (cost y a))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defn cudnn-custom-cost [prev-layer train-tz cost]
  (let [train-desc (desc train-tz)]
    (let-release [connect-output (connector (output prev-layer) train-desc)
                  connect-diff (connector train-desc (diff-z prev-layer))]
      (->CUDnnCustomCost prev-layer
                         connect-output connect-diff train-tz
                         (view-vctr (output connect-output)) (view-vctr train-tz)
                         (view-vctr (input connect-diff))
                         cost))))

;; ================================ Convolution =====================================

(deftype CUDnnConvolutionInference [fact cudnn-hdl bluep one zero
                                    conv-desc filter-desc conv-fwd-algo
                                    src-conn bias-tz weights-tz dst-tz workspace]
  Releaseable
  (release [_]
    (release src-conn)
    (release bias-tz)
    (release weights-tz)
    (release dst-tz)
    (release workspace)
    (release filter-desc))
  Info
  (info [this]
    {:bias (info bias-tz)
     :weights (info weights-tz)
     :dst (info dst-tz)})
  (info [this info-type]
    (case info-type
      :bias (info bias-tz)
      :weights (info weights-tz)
      :dst (info dst-tz)
      nil))
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    dst-tz)
  Parameters
  (bias [_]
    bias-tz)
  (weights [_]
    weights-tz)
  ParametersSeq
  (parameters [_]
    [weights-tz bias-tz])
  IFn
  (invoke [_]
    (src-conn)
    (convolution-fwd cudnn-hdl conv-desc conv-fwd-algo
                     one (output src-conn) (buffer (output src-conn))
                     filter-desc (buffer weights-tz) zero dst-tz (buffer dst-tz) workspace)
    (add-tensor cudnn-hdl one bias-tz (buffer bias-tz) one dst-tz (buffer dst-tz))
    dst-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(deftype CUDnnConvolutionTraining [fact cudnn-hdl bluep da one zero
                                   prop-diff? conv-desc filter-desc
                                   conv-fwd-algo conv-bwd-data-algo conv-bwd-weights-algo
                                   src-conn bias-tz weights-tz dst-tz
                                   diff-weights-tz diff-src-conn workspace]
  Releaseable
  (release [_]
    (release src-conn)
    (release bias-tz)
    (release weights-tz)
    (release dst-tz)
    (release diff-weights-tz)
    (release diff-src-conn)
    (release workspace)
    (release filter-desc))
  Info
  (info [this]
    {:bias (info bias-tz)
     :weights (info weights-tz)
     :dst (info dst-tz)
     :diff-weights (info diff-weights-tz)})
  (info [this info-type]
    (case info-type
      :bias (info bias-tz)
      :weights (info weights-tz)
      :dst (info dst-tz)
      :diff-weights (info diff-weights-tz)
      nil))
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    dst-tz)
  DiffTransfer
  (diff-input [_]
    dst-tz)
  (diff-output [_]
    (input src-conn))
  Parameters
  (bias [_]
    bias-tz)
  (weights [_]
    weights-tz)
  ParametersSeq
  (parameters [_]
    [weights-tz bias-tz])
  DiffParameters
  (diff-weights [_]
    diff-weights-tz)
  IFn
  (invoke [this]
    (forward this)
    dst-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs))
  Backprop
  (forward [this]
    (src-conn)
    (convolution-fwd cudnn-hdl conv-desc conv-fwd-algo
                     one (output src-conn) (buffer (output src-conn))
                     filter-desc (buffer weights-tz) zero dst-tz (buffer dst-tz) workspace)
    (add-tensor cudnn-hdl one bias-tz (buffer bias-tz) one dst-tz (buffer dst-tz))
    this)
  (backward [this]
    (backward-diff this one zero one zero))
  LinearBackprop
  (backward-diff [this scal-diff-w scal-g scal-diff-b scal-b]
    (convolution-bwd-filter cudnn-hdl conv-desc conv-bwd-weights-algo
                            (cast-prim da scal-diff-w)
                            (output src-conn) (buffer (output src-conn))
                            dst-tz (buffer dst-tz)
                            (cast-prim da scal-g) filter-desc (buffer diff-weights-tz)
                            workspace)
    (convolution-bwd-bias cudnn-hdl
                          (cast-prim da scal-diff-b) dst-tz (buffer dst-tz)
                          (cast-prim da scal-b) bias-tz (buffer bias-tz))
    (when prop-diff?
      (convolution-bwd-data cudnn-hdl conv-desc conv-bwd-data-algo
                            one filter-desc (buffer weights-tz)
                            dst-tz (buffer dst-tz)
                            zero (input diff-src-conn) (buffer (input diff-src-conn))
                            workspace)
      (diff-src-conn))
    this))

(deftype CUDnnConvolutionBlueprint [fact conv-desc
                                    conv-fwd-algo conv-bwd-data-algo conv-bwd-weights-algo
                                    src-desc weights-desc filter-desc bias-desc dst-desc]
  Releaseable
  (release [_]
    (release conv-desc)
    (release conv-fwd-algo)
    (release conv-bwd-data-algo)
    (release conv-bwd-weights-algo)
    (release src-desc)
    (release weights-desc)
    (release filter-desc)
    (release bias-desc)
    (release dst-desc))
  Object
  (hashCode [_]
    (-> (hash :inner-product)
        (hash-combine src-desc) (hash-combine weights-desc)
        (hash-combine bias-desc) (hash-combine dst-desc)))
  (equals [_ other]
    (and (instance? CUDnnConvolutionBlueprint other)
         (equal-desc? src-desc (.src-desc ^CUDnnConvolutionBlueprint other))
         (equal-desc? weights-desc (.weights-desc ^CUDnnConvolutionBlueprint other))
         (equal-desc? dst-desc (.dst-desc ^CUDnnConvolutionBlueprint other))))
  (toString [this]
    (pr-str {:src src-desc :weights weights-desc :dst dst-desc}))
  Info
  (info [this info-type]
    (case info-type
      :bias bias-desc
      :inference {:src src-desc
                  :weights weights-desc
                  :filter filter-desc
                  :dst dst-desc}
      :training {:src src-desc
                 :weights weights-desc
                 :filter filter-desc
                 :dst dst-desc}
      nil))
  (info [this]
    {:bias bias-desc
     :inference {:src src-desc
                 :weights weights-desc
                 :filter filter-desc
                 :dst dst-desc}
     :training {:src src-desc
                :weights weights-desc
                :filter filter-desc
                :dst dst-desc}})
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [_]
    (view dst-desc))
  (train-desc [_]
    (view dst-desc))
  TensorDescriptor
  (shape [_]
    (shape dst-desc))
  (data-type [_]
    (data-type dst-desc))
  (layout [_]
    (strides dst-desc))
  Workspace
  (inf-ws-size [this]
    (:workspace-size conv-fwd-algo))
  (train-ws-size [this]
    (max (long (:workspace-size conv-fwd-algo))
         (long (:workspace-size conv-bwd-data-algo))
         (long (:workspace-size conv-bwd-weights-algo))))
  IFn
  (invoke [this src-tz]
    (let-release [src-conn (connector src-tz src-desc)
                  bias-tz (cudnn-tensor fact (view bias-desc))
                  weights-tz (cudnn-tensor fact (view weights-desc))
                  a-tz (cudnn-tensor fact (view dst-desc))]
      (->CUDnnConvolutionInference fact (handle fact) this
                                   (cast-prim (data-accessor a-tz) 1.0)
                                   (cast-prim (data-accessor a-tz) 0.0)
                                   conv-desc (view filter-desc) (:algo conv-fwd-algo)
                                   src-conn bias-tz weights-tz a-tz *workspace*)))
  (invoke [this src-tz dst-tz prop-diff? _]
    (let [da (data-accessor dst-tz)]
      (let-release [src-conn (connector src-tz src-desc)
                    bias-tz (cudnn-tensor fact (view bias-desc))
                    weights-tz (cudnn-tensor fact (view weights-desc))
                    diff-src-conn (revert src-conn)
                    diff-weights-tz (create-tensor fact (view weights-desc) true)]
        (->CUDnnConvolutionTraining fact (handle fact) this da
                                    (cast-prim da 1.0) (cast-prim da 0.0)
                                    prop-diff? conv-desc (view filter-desc)
                                    (:algo conv-fwd-algo) (:algo conv-bwd-data-algo)
                                    (:algo conv-bwd-weights-algo)
                                    src-conn bias-tz weights-tz dst-tz
                                    diff-weights-tz diff-src-conn
                                    *workspace*))))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defn cudnn-convolution-op-blueprint
  [fact src-desc weights-desc dst-desc strides padding dilation]
  (let-release [src-desc (desc src-desc)
                dst-desc (desc dst-desc)
                dtype (data-type dst-desc)
                weights-desc (cudnn-tensor-desc (shape weights-desc) dtype :nchw)
                filter-desc (filter-descriptor (shape weights-desc) dtype :nchw)
                bias-desc (cudnn-tensor-desc [1 (get (dims dst-desc) 1)] dtype :nc)
                conv-desc (convolution-descriptor :cross-correleation dtype padding strides dilation)
                conv-fwd-algo (convolution-fwd-find-algo (handle fact) conv-desc
                                                         src-desc filter-desc dst-desc)
                conv-bwd-data-algo (convolution-bwd-data-find-algo (handle fact) conv-desc
                                                                   filter-desc dst-desc src-desc)
                conv-bwd-weights-algo (convolution-bwd-filter-find-algo (handle fact) conv-desc
                                                                        src-desc dst-desc filter-desc)]
    (->CUDnnConvolutionBlueprint fact conv-desc
                                 conv-fwd-algo conv-bwd-data-algo conv-bwd-weights-algo
                                 src-desc weights-desc filter-desc bias-desc dst-desc)))

(defn cudnn-convolution-layer-blueprint [fact src-desc weights-desc dst-desc activ
                                         strides padding dilation alpha]
  (let [dtype (or (tz/data-type src-desc) :float)]
    (let-release [src-desc (cudnn-tensor-desc (shape src-desc) dtype (layout src-desc))
                  dst-desc (cudnn-tensor-desc (shape dst-desc)
                                              (or (tz/data-type dst-desc) dtype)
                                              (layout dst-desc))
                  convolution-bluep (cudnn-convolution-op-blueprint
                                     fact src-desc weights-desc dst-desc strides padding dilation)
                  activ-bluep (cudnn-activ-blueprint fact (view dst-desc) activ alpha)]
      (->DirectedLayerBlueprint fact :convolution convolution-bluep activ-bluep))))

;; ================================ Pooling =============================================

(deftype CUDnnPoolingInferenceLayer [fact cudnn-hdl bluep pooling-desc
                                     src-tz dst-tz one zero]
  Releaseable
  (release [_]
    (release src-tz)
    (release dst-tz))
  Object
  (hashCode [_]
    (-> (hash :pooling)
        (hash-combine (shape src-tz))
        (hash-combine (shape dst-tz))))
  (equals [_ other]
    (and (instance? CUDnnPoolingInferenceLayer other)
         (= src-tz (.src-tz ^CUDnnPoolingInferenceLayer other))
         (= dst-tz (.dst-tz ^CUDnnPoolingInferenceLayer other))))
  (toString [this]
    (str bluep))
  Info
  (info [this]
    {:algo (info bluep :algo)
     :dst (info dst-tz)
     :shape (shape dst-tz)})
  (info [this info-type]
    (case info-type
      :algo (info bluep :algo)
      :dst (info dst-tz)
      (info bluep info-type)))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  Transfer
  (input [_]
    src-tz)
  (output [_]
    dst-tz)
  ParametersSeq
  (parameters [_]
    [])
  Initializable
  (init [this _]
    this)
  IFn
  (invoke [_]
    (pooling-forward cudnn-hdl pooling-desc
                     one src-tz (buffer src-tz) zero dst-tz (buffer dst-tz))
    dst-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnPoolingInferenceLayer
  [^CUDnnPoolingInferenceLayer layer ^java.io.Writer w]
  (.write w (format "#Pooling[shape:%s, algo:%s]\n destination: %s\n"
                    (shape (output layer)) (info layer :algo) (pr-str (.dst-tz layer)))))

(deftype CUDnnPoolingTrainingLayer [fact cudnn-hdl bluep pooling-desc
                                    src-tz dst-tz diff-dst-tz
                                    one zero prop-diff?]
  Releaseable
  (release [_]
    (release src-tz)
    (release dst-tz)
    (release diff-dst-tz))
  Object
  (hashCode [_]
    (-> (hash :pooling)
        (hash-combine (shape src-tz))
        (hash-combine (shape dst-tz))))
  (equals [_ other]
    (and (instance? CUDnnPoolingTrainingLayer other)
         (= src-tz (.src-tz ^CUDnnPoolingTrainingLayer other))
         (= dst-tz (.dst-tz ^CUDnnPoolingTrainingLayer other))))
  (toString [this]
    (str bluep))
  Info
  (info [this]
    {:algo (info bluep :algo)
     :dst (info dst-tz)
     :shape (shape dst-tz)})
  (info [this info-type]
    (case info-type
      :algo (info bluep :algo)
      :dst (info dst-tz)
      (info bluep info-type)))
  Transfer
  (input [_]
    src-tz)
  (output [_]
    dst-tz)
  DiffTransfer
  (diff-input [_]
    diff-dst-tz)
  (diff-output [_]
    src-tz)
  ParametersSeq
  (parameters [_]
    [])
  Initializable
  (init [this _]
    this)
  IFn
  (invoke [this]
    (forward this nil)
    dst-tz)
  (applyTo [this xs]
    (AFn/applyToHelper this xs))
  Backprop
  (forward [this]
    this)
  (forward [this _]
    (pooling-forward cudnn-hdl pooling-desc
                     one src-tz (buffer src-tz)
                     zero dst-tz (buffer dst-tz))
    this)
  (backward [this]
    this)
  (backward [this _]
    (when prop-diff?
      (pooling-backward cudnn-hdl pooling-desc
                        one dst-tz (buffer dst-tz) diff-dst-tz (buffer diff-dst-tz)
                        src-tz (buffer src-tz) zero src-tz (buffer src-tz)))
    this))

(defmethod print-method CUDnnPoolingTrainingLayer
  [^CUDnnPoolingTrainingLayer layer ^java.io.Writer w]
  (.write w (format "#Pooling[shape:%s, algo:%s]\n destination: %s\n"
                    (shape (output layer)) (info layer :algo) (pr-str (.dst-tz layer)))))

(deftype CUDnnPoolingBlueprint [fact algo pd dst-desc]
  Releaseable
  (release [_]
    (release pd)
    (release dst-desc))
  Object
  (hashCode [this]
    (-> (hash :pooling)
        (hash-combine algo)
        (hash-combine (train-desc this))))
  (equals [this other]
    (and (instance? CUDnnPoolingBlueprint other)
         (= algo (.algo ^CUDnnPoolingBlueprint other))
         (= (inf-desc this) (inf-desc other))
         (= (train-desc this) (train-desc other))))
  (toString [this]
    (str {:algo algo
          :shape (shape this)
          :topology :pooling}))
  Info
  (info [this]
    {:algo algo
     :shape (shape dst-desc)
     :topology :pooling})
  (info [this info-type]
    (case info-type
      :algo algo
      :shape (shape dst-desc)
      :topology :pooling
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [_]
    (view dst-desc))
  (train-desc [_]
    (view dst-desc))
  TensorDescriptor
  (shape [_]
    (shape dst-desc))
  (data-type [_]
    (tz/data-type dst-desc))
  (layout [_]
    (layout dst-desc))
  IFn
  (invoke [this prev-layer]
    (let-release [dst-tz (cudnn-tensor fact (view dst-desc))]
      (->CUDnnPoolingInferenceLayer fact (handle fact) this pd
                                    (view (output prev-layer)) dst-tz
                                    (cast-prim (data-accessor dst-tz) 1.0)
                                    (cast-prim (data-accessor dst-tz) 0.0))))
  (invoke [this prev-layer prop-diff? _]
    (let-release [dst-tz (cudnn-tensor fact (view dst-desc))
                  diff-dst-tz (cudnn-tensor fact (view dst-desc))]
      (->CUDnnPoolingTrainingLayer fact (handle fact) this pd
                                   (view (output prev-layer)) dst-tz diff-dst-tz
                                   (cast-prim (data-accessor dst-tz) 1.0)
                                   (cast-prim (data-accessor dst-tz) 0.0)
                                   prop-diff?)))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnPoolingBlueprint
  [bp ^java.io.Writer w]
  (.write w (str bp)))

(defn cudnn-pooling-blueprint
  [fact src-desc dst-desc algo strides kernel padding]
  (let-release [dst-desc (cudnn-tensor-desc (shape dst-desc)
                                            (or (tz/data-type dst-desc) (data-type src-desc))
                                            (or (tz/layout dst-desc) (default-strides (shape dst-desc))))
                pool-desc (pooling-descriptor algo kernel strides padding)]
    (->CUDnnPoolingBlueprint fact algo pool-desc dst-desc)))

(defmethod transfer! [CUDnnPoolingInferenceLayer Object]
  [source destination]
  destination)

(defmethod transfer! [CUDnnPoolingTrainingLayer Object]
  [source destination]
  destination)

;; ====================== Dropout ====================================================

(defn cudnn-gaussian-dropout-blueprint [fact src-desc sd]
  (let-release [mask-desc (cudnn-contiguous-desc (desc src-desc))]
    (->GaussianDropoutBlueprint fact sd mask-desc)))

;; ====================== Batch Normalization =======================================

(deftype CUDnnBatchNormalizationInference [fact cudnn-hdl bluep one zero param-desc
                                           src-conn gamma-tz beta-tz mean-tz var-tz]
  Releaseable
  (release [_]
    (release src-conn)
    (release gamma-tz)
    (release beta-tz)
    (release mean-tz)
    (release var-tz))
  Object
  (hashCode [_]
    (-> (hash :batch-norm)
        (hash-combine (shape gamma-tz))
        (hash-combine (shape beta-tz))
        (hash-combine (shape (input src-conn)))
        (hash-combine (shape (output src-conn)))))
  (equals [_ other]
    (and (instance? CUDnnBatchNormalizationInference other)
         (= gamma-tz (.gamma-tz ^CUDnnBatchNormalizationInference other))
         (= beta-tz (.beta-tz ^CUDnnBatchNormalizationInference other))
         (= src-conn (.src-conn ^CUDnnBatchNormalizationInference other))
         (= mean-tz (.mean-tz ^CUDnnBatchNormalizationInference other))
         (= var-tz (.var-tz ^CUDnnBatchNormalizationInference other))
         (= (output src-conn) (output (.src-conn ^CUDnnBatchNormalizationInference other)))))
  (toString [this]
    (str bluep))
  Info
  (info [this]
    {:gamma (info gamma-tz)
     :beta (info beta-tz)
     :dst (info (output src-conn))})
  (info [this info-type]
    (case info-type
      :gamma (info gamma-tz)
      :beta (info beta-tz)
      :dst (info (output src-conn))
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    (output src-conn))
  Parameters
  (weights [_]
    gamma-tz)
  (bias [_]
    beta-tz)
  ParametersSeq
  (parameters [_]
    [gamma-tz beta-tz mean-tz var-tz])
  Initializable
  (init [this init-fn]
    (init-fn gamma-tz)
    (init-fn beta-tz)
    this)
  IFn
  (invoke [_]
    (src-conn)
    (batch-norm-fwd-inference cudnn-hdl :spatial one zero
                              (output src-conn) (buffer (output src-conn))
                              (output src-conn) (buffer (output src-conn))
                              param-desc (buffer gamma-tz) (buffer beta-tz)
                              (buffer mean-tz) (buffer var-tz))
    (output src-conn))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnBatchNormalizationInference
  [layer ^java.io.Writer w]
  (.write w (format "#BatchNorm[]\n gamma:\n beta: \n output: %s\n"
                    (weights layer) (bias layer) (pr-str (output layer)))))

(deftype CUDnnBatchNormalizationTraining [fact cudnn-hdl bluep da cnt un-bessel one zero
                                          param-desc src-conn gamma-tz beta-tz dst-tz
                                          mean-tz var-tz saved-mean-tz saved-inv-var-tz
                                          diff-gamma-tz diff-beta-tz diff-src-conn]
  Releaseable
  (release [_]
    (release src-conn)
    (release gamma-tz)
    (release beta-tz)
    (release dst-tz)
    (release mean-tz)
    (release var-tz)
    (release saved-mean-tz)
    (release saved-inv-var-tz)
    (release diff-gamma-tz)
    (release diff-beta-tz)
    (release diff-src-conn))
  Object
  (hashCode [_]
    (-> (hash :batch-norm)
        (hash-combine (shape gamma-tz))
        (hash-combine (shape beta-tz))
        (hash-combine (shape (input src-conn)))
        (hash-combine (shape (output src-conn)))))
  (equals [_ other]
    (and (instance? CUDnnBatchNormalizationInference other)
         (= gamma-tz (.gamma-tz ^CUDnnBatchNormalizationTraining other))
         (= beta-tz (.beta-tz ^CUDnnBatchNormalizationTraining other))
         (= src-conn (.src-conn ^CUDnnBatchNormalizationTraining other))
         (= mean-tz (.mean-tz ^CUDnnBatchNormalizationTraining other))
         (= var-tz (.var-tz ^CUDnnBatchNormalizationTraining other))
         (= dst-tz (output (.dst-tz ^CUDnnBatchNormalizationTraining other)))))
  (toString [this]
    (str bluep))
  Info
  (info [this]
    {:gamma (info gamma-tz)
     :beta (info beta-tz)
     :dst (info dst-tz)
     :mean (info mean-tz)
     :variance (info var-tz)
     :diff-diff-gamma (info diff-gamma-tz)})
  (info [this info-type]
    (case info-type
      :gamma (info gamma-tz)
      :beta (info beta-tz)
      :dst (info dst-tz)
      :mean (info mean-tz)
      :variance (info var-tz)
      :diff-diff-gamma (info diff-gamma-tz)
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  Transfer
  (input [_]
    (input src-conn))
  (output [_]
    dst-tz)
  DiffTransfer
  (diff-input [_]
    dst-tz)
  (diff-output [_]
    (output diff-src-conn))
  Parameters
  (weights [_]
    gamma-tz)
  (bias [_]
    beta-tz)
  ParametersSeq
  (parameters [_]
    [gamma-tz beta-tz mean-tz var-tz])
  Initializable
  (init [this init-fn]
    (init-fn gamma-tz)
    (init-fn beta-tz)
    (reset! cnt 0)
    this)
  DiffParameters
  (diff-weights [_]
    diff-gamma-tz)
  IFn
  (invoke [_]
    (src-conn)
    (batch-norm-fwd-training cudnn-hdl :spatial one zero
                             (output src-conn) (buffer (output src-conn))
                             dst-tz (buffer dst-tz)
                             param-desc (buffer gamma-tz) (buffer beta-tz) @cnt
                             (buffer mean-tz) (buffer var-tz)
                             (buffer saved-mean-tz) (buffer saved-inv-var-tz))
    (scal! un-bessel var-tz)
    dst-tz)
  Backprop
  (forward [this]
    (src-conn)
    (batch-norm-fwd-training cudnn-hdl :spatial one zero
                             (output src-conn) (buffer (output src-conn))
                             dst-tz (buffer dst-tz)
                             param-desc (buffer gamma-tz) (buffer beta-tz) (swap! cnt inc)
                             (buffer mean-tz) (buffer var-tz)
                             (buffer saved-mean-tz) (buffer saved-inv-var-tz))
    (scal! un-bessel var-tz)
    this)
  (backward [this]
    (backward-diff this 1.0 0.0 1.0 0.0))
  LinearBackprop ;; TODO take prop-diff into account?
  (backward-diff [this scal-diff-w scal-g _ scal-b]
    (entry! diff-beta-tz 0.0)
    (batch-norm-bwd cudnn-hdl :spatial one zero
                    (cast-prim da scal-diff-w) (cast-prim da scal-g)
                    (output src-conn) (buffer (output src-conn))
                    dst-tz (buffer dst-tz) (output src-conn) (buffer (output src-conn))
                    param-desc (buffer gamma-tz) (buffer diff-gamma-tz) (buffer diff-beta-tz)
                    (buffer saved-mean-tz) (buffer saved-inv-var-tz))
    (axpby! 1.0 diff-beta-tz scal-b beta-tz)
    (diff-src-conn)
    this)
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnBatchNormalizationTraining
  [layer ^java.io.Writer w]
  (.write w (format "#BatchNorm[]\n gamma:\n beta: \n output: %s\n"
                    (weights layer) (bias layer) (pr-str (output layer)))))


(deftype CUDnnBatchNormalizationBlueprint [fact data-desc gamma-desc un-bessel]
  Releaseable
  (release [_]
    (release data-desc)
    (release gamma-desc))
  Object
  (hashCode [_]
    (-> (hash :batch-norm) (hash-combine gamma-desc)))
  (equals [_ other]
    (and (instance? CUDnnBatchNormalizationBlueprint other)
         (equal-desc? data-desc (.data-desc ^CUDnnBatchNormalizationBlueprint other))
         (equal-desc? gamma-desc (.gamma-desc ^CUDnnBatchNormalizationBlueprint other))))
  (toString [this]
    (pr-str {:topology :batch-norm
             :shape (shape this)}))
  Info
  (info [this info-type]
    (case info-type
      :bias gamma-desc
      :inference {:src data-desc
                  :weights gamma-desc
                  :dst data-desc}
      :training {:src data-desc
                 :weights gamma-desc
                 :dst data-desc}
      nil))
  (info [this]
    {:bias gamma-desc
     :inference {:src data-desc
                 :weights gamma-desc
                 :dst data-desc}
     :training {:src data-desc
                :weights gamma-desc
                :dst data-desc}})
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [_]
    data-desc)
  (train-desc [_]
    data-desc)
  TensorDescriptor
  (shape [this]
    (shape data-desc))
  (data-type [this]
    (data-type data-desc))
  (layout [this]
    (layout data-desc))
  IFn
  (invoke [this src-tz]
    (let-release [src-conn (connector src-tz data-desc)
                  gamma-tz (cudnn-tensor fact (view gamma-desc))
                  beta-tz (cudnn-tensor fact (view gamma-desc))
                  mean-tz (cudnn-tensor fact (view gamma-desc))
                  var-tz (cudnn-tensor fact (view gamma-desc))
                  dst-tz (cudnn-tensor fact (view gamma-desc))]
      (->CUDnnBatchNormalizationInference fact (handle fact) this
                                          (cast-prim (data-accessor gamma-tz) 1.0)
                                          (cast-prim (data-accessor gamma-tz) 0.0)
                                          gamma-desc src-conn gamma-tz beta-tz mean-tz var-tz)))
  (invoke [this src-tz dst-tz _ _] ;;TODO see about handling prop-diff?
    (let [da (data-accessor dst-tz)]
      (let-release [src-conn (connector src-tz data-desc)
                    gamma-tz (cudnn-tensor fact (view gamma-desc))
                    beta-tz (cudnn-tensor fact (view gamma-desc))
                    mean-tz (cudnn-tensor fact (view gamma-desc))
                    var-tz (cudnn-tensor fact (view gamma-desc))
                    saved-mean-tz (create-tensor fact (view gamma-desc) true)
                    saved-inv-var-tz (create-tensor fact (view gamma-desc) true)
                    diff-gamma-tz (create-tensor fact (view gamma-desc) true)
                    diff-beta-tz (create-tensor fact (view gamma-desc) true)
                    diff-src-conn (revert src-conn)]
        (->CUDnnBatchNormalizationTraining fact (handle fact) this da (atom -1) un-bessel
                                           (cast-prim da 1.0) (cast-prim da 0.0)
                                           gamma-desc src-conn gamma-tz beta-tz dst-tz
                                           mean-tz var-tz saved-mean-tz saved-inv-var-tz
                                           diff-gamma-tz diff-beta-tz diff-src-conn))))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnBatchNormalizationBlueprint
  [bp ^java.io.Writer w]
  (.write w (str bp)))

(defn cudnn-batch-norm-op-blueprint [fact data-desc]
  (let-release [data-desc (desc data-desc)
                gamma-desc (batch-norm-descriptor data-desc :spatial)]
    (let [data-shape (shape data-desc)
          n (long (apply * (get data-shape 0) (drop 2 data-shape)))
          un-bessel (/ (dec n) n)]
      (->CUDnnBatchNormalizationBlueprint fact data-desc gamma-desc un-bessel))))

(defn cudnn-batch-norm-layer-blueprint [fact data-desc activ alpha beta]
  (let-release [batch-norm-bluep (cudnn-batch-norm-op-blueprint fact (view data-desc))
                activ-bluep (cudnn-activ-blueprint fact (view data-desc) activ alpha)]
    (->DirectedLayerBlueprint fact :batch-norm batch-norm-bluep activ-bluep)))

(defmethod transfer! [CUDnnBatchNormalizationInference Object]
  [source destination]
  (doall (map transfer! (parameters source) (parameters destination)))
  destination)

(defmethod transfer! [CUDnnBatchNormalizationTraining Object]
  [source destination]
  (doall (map transfer! (parameters source) (parameters destination)))
  destination)

;; ================================ Sum ======================================

(deftype CUDnnSum [fact cudnn-hdl bluep src-tzs prop-diff?]
  Releaseable
  (release [_]
    true)
  Object
  (hashCode [_]
    (reduce #(hash-combine %1 (shape %2)) (hash :sum) src-tzs))
  (equals [_ other]
    (and (instance? CUDnnSum other) (= src-tzs (.src-tzs ^CUDnnSum other))))
  (toString [this]
    (str bluep))
  Info
  (info [this]
    {:src (map info src-tzs)})
  (info [this info-type]
    (case info-type
      :src (map info src-tzs)
      nil))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  Transfer
  (input [_]
    src-tzs)
  (output [_]
    (first src-tzs))
  DiffTransfer
  (diff-input [_]
    (first src-tzs))
  (diff-output [_]
    src-tzs)
  ParametersSeq
  (parameters [_]
    [])
  Initializable
  (init [this _]
    this)
  Backprop
  (forward [this]
    this)
  (forward [this _]
    (let [src0 (get src-tzs 0)]
      (doseq [src-tz (rest src-tzs)]
        (axpy! 1.0 src-tz src0)))
    this)
  (backward [this]
    this)
  (backward [this _]
    (when prop-diff?
      (let [src0 (scal! (/ 1.0 (count src-tzs)) (get src-tzs 0))]
        (doseq [src-tz (rest src-tzs)]
          (copy! src0 src-tz))))
    this)
  IFn
  (invoke [this]
    (let [src0 (get src-tzs 0)]
      (doseq [src-tz (rest src-tzs)]
        (axpy! 1.0 src-tz src0))
      src0))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnSum
  [layer ^java.io.Writer w]
  (.write w (format "#Sum[srcs:%s]" (input layer))))

(deftype CUDnnSumBlueprint [fact src-descs]
  Releaseable
  (release [_]
    (doseq [sd src-descs]
      (release sd))
    true)
  Object
  (hashCode [_]
    (reduce #(hash-combine %1 (shape %2)) (hash :sum) src-descs))
  (equals [_ other]
    (and (instance? CUDnnSumBlueprint other)
         (equal-desc? src-descs (.src-descs ^CUDnnSumBlueprint other))))
  (toString [this]
    (pr-str {:shape (shape this)
             :topology :sum}))
  DiamondFactoryProvider
  (diamond-factory [_]
    fact)
  DescriptorProvider
  (inf-desc [this]
    (get src-descs 0))
  (train-desc [_]
    (get src-descs 0))
  TensorDescriptor
  (shape [_]
    (shape (get src-descs 0)))
  (data-type [_]
    (data-type (get src-descs 0)))
  (layout [_]
    (layout (get src-descs 0)))
  IFn
  (invoke [this prev-layer]
    (this prev-layer false nil))
  (invoke [this prev-layer prop-diff? _]
    (->CUDnnSum fact (handle fact) this (fmap output prev-layer) prop-diff?))
  (applyTo [this xs]
    (AFn/applyToHelper this xs)))

(defmethod print-method CUDnnSum
  [bp ^java.io.Writer w]
  (.write w (str bp)))

(defn cudnn-sum-blueprint [fact src-descs]
  (->CUDnnSumBlueprint fact (mapv (comp view desc) src-descs)))

(defmethod transfer! [CUDnnSum Object]
  [source destination]
  destination)
