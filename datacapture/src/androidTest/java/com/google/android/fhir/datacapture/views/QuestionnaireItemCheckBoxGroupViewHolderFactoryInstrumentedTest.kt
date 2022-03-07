/*
 * Copyright 2021 Google LLC
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

package com.google.android.fhir.datacapture.views

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.fhir.datacapture.ChoiceOrientationTypes
import com.google.android.fhir.datacapture.EXTENSION_CHOICE_ORIENTATION_URL
import com.google.android.fhir.datacapture.R
import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionnaireItemCheckBoxGroupViewHolderFactoryInstrumentedTest {
  private val parent =
    FrameLayout(
      ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.Theme_Questionnaire
      )
    )
  private val viewHolder = QuestionnaireItemCheckBoxGroupViewHolderFactory.create(parent)

  @Test
  fun shouldShowPrefixText() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          prefix = "Prefix?"
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.prefix_text_view).isVisible).isTrue()
    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.prefix_text_view).text.toString())
      .isEqualTo("Prefix?")
  }

  @Test
  fun shouldHidePrefixText() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          prefix = ""
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.prefix_text_view).isVisible)
      .isFalse()
  }

  @Test
  fun bind_shouldSetQuestionText() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          text = "Question?"
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.question_text_view).text.toString())
      .isEqualTo("Question?")
  }

  @Test
  fun bind_vertical_shouldCreateCheckBoxButtons() {
    val questionnaire =
      Questionnaire.QuestionnaireItemComponent().apply {
        repeats = true
        addExtension(
          EXTENSION_CHOICE_ORIENTATION_URL,
          CodeType(ChoiceOrientationTypes.VERTICAL.extensionCode)
        )
        addAnswerOption(
          Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
            value = Coding().apply { display = "Coding 1" }
          }
        )
        addAnswerOption(
          Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
            value = Coding().apply { display = "Coding 2" }
          }
        )
      }
    viewHolder.bind(
      QuestionnaireItemViewItem(
        questionnaire,
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val children = checkBoxGroup.children.asIterable().filterIsInstance<CheckBox>()
    children.forEachIndexed { index, view ->
      assertThat(view.text).isEqualTo(questionnaire.answerOption[index].valueCoding.display)
      assertThat(view.layoutParams.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
    }
  }

  @Test
  fun bind_horizontal_shouldCreateCheckBoxButtons() {
    val questionnaire =
      Questionnaire.QuestionnaireItemComponent().apply {
        repeats = true
        addExtension(
          EXTENSION_CHOICE_ORIENTATION_URL,
          CodeType(ChoiceOrientationTypes.HORIZONTAL.extensionCode)
        )
        addAnswerOption(
          Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
            value = Coding().apply { display = "Coding 1" }
          }
        )
        addAnswerOption(
          Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
            value = Coding().apply { display = "Coding 2" }
          }
        )
      }
    viewHolder.bind(
      QuestionnaireItemViewItem(
        questionnaire,
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val children = checkBoxGroup.children.asIterable().filterIsInstance<CheckBox>()
    children.forEachIndexed { index, view ->
      assertThat(view.text).isEqualTo(questionnaire.answerOption[index].valueCoding.display)
      assertThat(view.layoutParams.width).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
    }
  }

  @Test
  fun bind_noAnswer_shouldLeaveCheckButtonsUnchecked() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          addAnswerOption(
            Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
              value = Coding().apply { display = "Coding 1" }
            }
          )
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val checkBox = checkBoxGroup.getChildAt(1) as CheckBox
    assertThat(checkBox.isChecked).isFalse()
  }

  @Test
  @UiThreadTest
  fun bind_answer_shouldSetCheckBoxButton() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          addAnswerOption(
            Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
              value =
                Coding().apply {
                  code = "code 1"
                  display = "Coding 1"
                }
            }
          )
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
              value =
                Coding().apply {
                  code = "code 1"
                  display = "Coding 1"
                }
            }
          )
        }
      ) {}
    )
    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val checkBox = checkBoxGroup.getChildAt(1) as CheckBox

    assertThat(checkBox.isChecked).isTrue()
  }

  @Test
  @UiThreadTest
  fun click_shouldAddQuestionnaireResponseItemAnswer() {
    val questionnaireItemViewItem =
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          addAnswerOption(
            Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
              value =
                Coding().apply {
                  code = "code 1"
                  display = "Coding 1"
                }
            }
          )
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    viewHolder.bind(questionnaireItemViewItem)
    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val checkBox = checkBoxGroup.getChildAt(1) as CheckBox
    checkBox.performClick()
    val answer = questionnaireItemViewItem.questionnaireResponseItem.answer

    assertThat(answer).hasSize(1)
    assertThat(answer[0].valueCoding.display).isEqualTo("Coding 1")
  }

  @Test
  @UiThreadTest
  fun click_shouldRemoveQuestionnaireResponseItemAnswer() {
    val questionnaireItemViewItem =
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          answerValueSet = "http://coding-value-set-url"
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
              value =
                Coding().apply {
                  code = "code 1"
                  display = "Coding 1"
                }
            }
          )
        },
        resolveAnswerValueSet = {
          if (it == "http://coding-value-set-url") {
            listOf(
              Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
                value =
                  Coding().apply {
                    code = "code 1"
                    display = "Coding 1"
                  }
              }
            )
          } else {
            emptyList()
          }
        }
      ) {}
    viewHolder.bind(questionnaireItemViewItem)
    val checkBoxGroup = viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group)
    val checkBox = checkBoxGroup.getChildAt(1) as CheckBox
    checkBox.performClick()
    val answer = questionnaireItemViewItem.questionnaireResponseItem.answer

    assertThat(answer).isEmpty()
  }

  @Test
  @UiThreadTest
  fun displayValidationResult_noError_shouldShowNoErrorMessageAtStart() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          required = true
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.error_text_view).text)
      .isEqualTo("")
  }

  @Test
  @UiThreadTest
  fun displayValidationResult_noError_shouldShowNoErrorMessage() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          required = true
          addAnswerOption(
            Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
              value = Coding().apply { display = "display" }
            }
          )
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
              value = Coding().apply { display = "display" }
            }
          )
        }
      ) {}
    )

    assertThat(viewHolder.itemView.findViewById<TextView>(R.id.error_text_view).text.isEmpty())
      .isTrue()
  }

  @Test
  fun bind_readOnly_shouldDisableView() {
    viewHolder.bind(
      QuestionnaireItemViewItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          repeats = true
          readOnly = true
          addAnswerOption(
            Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
              value = Coding().apply { display = "Coding 1" }
            }
          )
        },
        QuestionnaireResponse.QuestionnaireResponseItemComponent()
      ) {}
    )

    assertThat(
        (viewHolder.itemView.findViewById<ConstraintLayout>(R.id.checkbox_group).getChildAt(1) as
            CheckBox)
          .isEnabled
      )
      .isFalse()
  }
}
