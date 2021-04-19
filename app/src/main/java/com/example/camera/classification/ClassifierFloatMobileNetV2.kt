package com.example.camera.classification

import android.content.Context

class ClassifierFloatMobileNetV2(
    context: Context,
    numberOfThreads: Int
) : BaseClassifier(context, numberOfThreads) {

    override fun provideModelFilePath() = "classification/classification_model.tflite"

    override fun provideLabelsFilePath() = "classification/labels.txt"

}