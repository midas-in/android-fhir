/*
 * Copyright 2022-2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.views.factories

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.displayString
import com.google.android.fhir.datacapture.extensions.getRequiredOrOptionalText
import com.google.android.fhir.datacapture.extensions.getValidationErrorMessage
import com.google.android.fhir.datacapture.extensions.identifierString
import com.google.android.fhir.datacapture.extensions.itemAnswerOptionImage
import com.google.android.fhir.datacapture.extensions.localizedFlyoverSpanned
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.views.HeaderView
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.hl7.fhir.r4.model.QuestionnaireResponse
import timber.log.Timber

internal object DropDownViewHolderFactory :
  QuestionnaireItemViewHolderFactory(R.layout.drop_down_view) {
  override fun getQuestionnaireItemViewHolderDelegate() =
    object : QuestionnaireItemViewHolderDelegate {
      private lateinit var header: HeaderView
      private lateinit var textInputLayout: TextInputLayout
      private lateinit var autoCompleteTextView: MaterialAutoCompleteTextView
      private lateinit var clearIcon: ImageView
      override lateinit var questionnaireViewItem: QuestionnaireViewItem
      private lateinit var context: Context
      private var isDropdownEditable = true

      override fun init(itemView: View) {
        header = itemView.findViewById(R.id.header)
        textInputLayout = itemView.findViewById(R.id.text_input_layout)
        autoCompleteTextView = itemView.findViewById(R.id.auto_complete)
        clearIcon = itemView.findViewById(R.id.clearIcon)
        context = itemView.context
      }

      override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
        cleanupOldState()
        header.bind(questionnaireViewItem)
        with(textInputLayout) {
          hint = questionnaireViewItem.enabledDisplayItems.localizedFlyoverSpanned
          helperText = getRequiredOrOptionalText(questionnaireViewItem, context)
        }
        val answerOptionList =
          this.questionnaireViewItem.enabledAnswerOptions
            .map {
              DropDownAnswerOption(
                it.value.identifierString(context),
                it.value.displayString(context),
                it.itemAnswerOptionImage(context),
              )
            }
            .toMutableList()
        answerOptionList.add(
          0,
          DropDownAnswerOption(
            context.getString(R.string.hyphen),
            context.getString(R.string.hyphen),
            null,
          ),
        )
        val adapter =
          AnswerOptionDropDownArrayAdapter(context, R.layout.drop_down_list_item, answerOptionList)
        val selectedAnswerIdentifier =
          questionnaireViewItem.answers.singleOrNull()?.value?.identifierString(header.context)
        answerOptionList
          .firstOrNull { it.answerId == selectedAnswerIdentifier }
          ?.let {
            autoCompleteTextView.setText(it.answerOptionString)
            autoCompleteTextView.setSelection(it.answerOptionString.length)
            autoCompleteTextView.setCompoundDrawablesRelative(
              it.answerOptionImage,
              null,
              null,
              null,
            )
          }

        autoCompleteTextView.setAdapter(adapter)
        autoCompleteTextView.onItemClickListener =
          AdapterView.OnItemClickListener { _, _, position, _ ->
            if (isDropdownEditable) {
              val selectedItem = adapter.getItem(position)
              autoCompleteTextView.setText(selectedItem?.answerOptionString, false)
              autoCompleteTextView.setCompoundDrawablesRelative(
                adapter.getItem(position)?.answerOptionImage,
                null,
                null,
                null,
              )

              isDropdownEditable = false
              val selectedAnswer =
                questionnaireViewItem.enabledAnswerOptions
                  .firstOrNull { it.value.identifierString(context) == selectedItem?.answerId }
                  ?.value

              if (selectedAnswer == null) {
                questionnaireViewItem.clearAnswer()
              } else {
                questionnaireViewItem.setAnswer(
                  QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                    .setValue(selectedAnswer),
                )
              }
            }
          }

      autoCompleteTextView.doAfterTextChanged {
          if (it.isNullOrBlank()) {
            Handler(Looper.getMainLooper())
              .postDelayed(
                {
                  if (autoCompleteTextView.isPopupShowing.not()) {
                    autoCompleteTextView.showDropDown()
                  }
                },
                100,
              )
          }
        }

        displayValidationResult(questionnaireViewItem.validationResult)
      }

      private fun displayValidationResult(validationResult: ValidationResult) {
        textInputLayout.error =
          getValidationErrorMessage(
            textInputLayout.context,
            questionnaireViewItem,
            validationResult,
          )
      }

      override fun setReadOnly(isReadOnly: Boolean) {
        textInputLayout.isEnabled = !isReadOnly
        autoCompleteTextView.isEnabled = isDropdownEditable
      }

      private fun cleanupOldState() {
        autoCompleteTextView.setAdapter(null)
        autoCompleteTextView.text = null
        autoCompleteTextView.setCompoundDrawablesRelative(null, null, null, null)
      }
    }
}

internal class AnswerOptionDropDownArrayAdapter(
  context: Context,
  private val layoutResourceId: Int,
  answerOption: List<DropDownAnswerOption>,
) : ArrayAdapter<DropDownAnswerOption>(context, layoutResourceId, answerOption) {
  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val listItemView =
      convertView ?: LayoutInflater.from(parent.context).inflate(layoutResourceId, parent, false)
    try {
      val answerOption: DropDownAnswerOption? = getItem(position)
      val answerOptionTextView =
        listItemView?.findViewById<View>(R.id.answer_option_textview) as TextView
      answerOptionTextView.text = answerOption?.answerOptionString
      answerOptionTextView.setCompoundDrawablesRelative(
        answerOption?.answerOptionImage,
        null,
        null,
        null,
      )
    } catch (e: Exception) {
      Timber.w("Could not set data to dropdown UI", e)
    }
    return listItemView
  }
}

internal data class DropDownAnswerOption(
  val answerId: String,
  val answerOptionString: String,
  val answerOptionImage: Drawable? = null,
) {
  override fun toString(): String {
    return this.answerOptionString
  }
}
