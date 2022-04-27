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

package com.google.android.fhir.datacapture

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import com.google.android.fhir.datacapture.enablement.EnablementEvaluator
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator.checkQuestionnaireResponse
import com.google.android.fhir.datacapture.views.QuestionnaireItemViewItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.hl7.fhir.exceptions.PathEngineException
import org.hl7.fhir.instance.model.api.IBaseDatatype
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Type
import org.hl7.fhir.r4.model.ValueSet
import org.hl7.fhir.r4.utils.FHIRPathEngine
import timber.log.Timber

internal class QuestionnaireViewModel(application: Application, state: SavedStateHandle) :
  AndroidViewModel(application) {
  /** The current questionnaire as questions are being answered. */
  internal val questionnaire: Questionnaire

  init {
    questionnaire =
      when {
        state.contains(QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_URI) -> {
          if (state.contains(QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_STRING)) {
            Timber.w(
              "Both EXTRA_QUESTIONNAIRE_URI & EXTRA_JSON_ENCODED_QUESTIONNAIRE are provided. " +
                "EXTRA_QUESTIONNAIRE_URI takes precedence."
            )
          }
          val uri: Uri = state[QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_URI]!!
          FhirContext.forCached(FhirVersionEnum.R4)
            .newJsonParser()
            .parseResource(application.contentResolver.openInputStream(uri)) as
            Questionnaire
        }
        state.contains(QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_STRING) -> {
          val questionnaireJson: String =
            state[QuestionnaireFragment.EXTRA_QUESTIONNAIRE_JSON_STRING]!!
          FhirContext.forCached(FhirVersionEnum.R4)
            .newJsonParser()
            .parseResource(questionnaireJson) as
            Questionnaire
        }
        else ->
          error("Neither EXTRA_QUESTIONNAIRE_URI nor EXTRA_JSON_ENCODED_QUESTIONNAIRE is supplied.")
      }
  }

  /** The current questionnaire response as questions are being answered. */
  private val questionnaireResponse: QuestionnaireResponse

  init {
    val questionnaireJsonResponseString: String? =
      state[QuestionnaireFragment.EXTRA_QUESTIONNAIRE_RESPONSE_JSON_STRING]
    if (questionnaireJsonResponseString != null) {
      questionnaireResponse =
        FhirContext.forCached(FhirVersionEnum.R4)
          .newJsonParser()
          .parseResource(questionnaireJsonResponseString) as
          QuestionnaireResponse
      checkQuestionnaireResponse(questionnaire, questionnaireResponse)
    } else {
      questionnaireResponse =
        QuestionnaireResponse().apply {
          questionnaire = this@QuestionnaireViewModel.questionnaire.url
        }
      // Retain the hierarchy and order of items within the questionnaire as specified in the
      // standard. See https://www.hl7.org/fhir/questionnaireresponse.html#notes.
      questionnaire.item.forEach {
        questionnaireResponse.addItem(it.createQuestionnaireResponseItem())
      }
    }
  }

  /** Map from link IDs to questionnaire response items. */
  private val linkIdToQuestionnaireResponseItemMap =
    createLinkIdToQuestionnaireResponseItemMap(questionnaireResponse.item)

  private val linkIdToQuestionnaireItemPathMap =
    createLinkIdToQuestionnaireItemPathMap(questionnaire.item)

  private val questionnaireResponseItemPathToVariableMap =
    createQuestionnaireResponseItemPathToVariableMap(questionnaireResponse.item)

  /** Map from link IDs to questionnaire items. */
  private val linkIdToQuestionnaireItemMap = createLinkIdToQuestionnaireItemMap(questionnaire.item)

  /** Tracks modifications in order to update the UI. */
  private val modificationCount = MutableStateFlow(0)

  /**
   * Callback function to update the UI which takes the linkId of the question whose answer(s) has
   * been changed.
   */
  private val questionnaireResponseItemChangedCallback: (String) -> Unit = { linkId ->
    linkIdToQuestionnaireItemMap[linkId]?.let { questionnaireItem ->
      if (questionnaireItem.hasNestedItemsWithinAnswers) {
        linkIdToQuestionnaireResponseItemMap[linkId]?.let { questionnaireResponseItem ->
          questionnaireResponseItem.addNestedItemsToAnswer(questionnaireItem)
          questionnaireResponseItem.answer.singleOrNull()?.item?.forEach {
            nestedQuestionnaireResponseItem ->
            linkIdToQuestionnaireResponseItemMap[nestedQuestionnaireResponseItem.linkId] =
              nestedQuestionnaireResponseItem
          }
        }
      }
    }
    calculateVariables()
    modificationCount.value += 1
  }

  private fun calculateVariables() {
    calculateRootVariables()

    for ((key, _) in linkIdToQuestionnaireItemPathMap) {
      linkIdToQuestionnaireItemMap[key]?.let { questionnaireItem ->
        linkIdToQuestionnaireResponseItemMap[key]?.let { questionnaireResponseItem ->
          calculateItemVariables(questionnaireItem, questionnaireResponseItem)
        }
      }
    }
  }

  private fun calculateItemVariables(
    questionnaireItem: Questionnaire.QuestionnaireItemComponent,
    questionnaireResponseItem: QuestionnaireResponse.QuestionnaireResponseItemComponent
  ) {
    val fhirPathEngine: FHIRPathEngine =
      with(FhirContext.forCached(FhirVersionEnum.R4)) {
        FHIRPathEngine(HapiWorkerContext(this, DefaultProfileValidationSupport(this))).apply {
          hostServices = FHIRPathEngineHostServices
        }
      }

    questionnaireItem.extension.forEach { extension ->
      if (extension.url == VARIABLE_EXTENSION_URL) {

        val variableValue =
          evaluateItemVariables(fhirPathEngine, extension, questionnaireResponseItem)

        val variables =
          questionnaireResponseItemPathToVariableMap[
            linkIdToQuestionnaireItemPathMap[questionnaireResponseItem.linkId]]
        updateVariable(variables, extension, variableValue)
      }
    }
  }

  private fun updateVariable(
    variables: MutableList<Variable>?,
    extension: Extension,
    variableValue: Any?
  ) {
    variables?.find { it.id == (extension.value as Expression).name }.also { variable ->
      if (variable == null) {
        variableValue?.let {
          variables?.add(Variable(id = (extension.value as Expression).name, value = it as Type))
        }
      } else {
        if (variableValue != null) variable.value = variableValue as Type
        else variables?.remove(variable)
      }
    }
  }

  private fun evaluateItemVariables(
    fhirPathEngine: FHIRPathEngine,
    extension: Extension,
    questionnaireResponseItem: QuestionnaireResponse.QuestionnaireResponseItemComponent
  ) =
    try {
      fhirPathEngine
        .evaluate(
          findAncestorVariables(extension, questionnaireResponseItem),
          questionnaireResponse,
          questionnaireResponse,
          questionnaireResponse,
          (extension.value as Expression).expression
        )
        .firstOrNull()
    } catch (exception: PathEngineException) {
      Timber.d("Could not evaluate expression with FHIRPathEngine", exception)
    }

  private fun calculateRootVariables() {
    val fhirPathEngine: FHIRPathEngine =
      with(FhirContext.forCached(FhirVersionEnum.R4)) {
        FHIRPathEngine(HapiWorkerContext(this, DefaultProfileValidationSupport(this))).apply {
          hostServices = FHIRPathEngineHostServices
        }
      }

    questionnaire.extension.forEach { extension ->
      if (extension.url == VARIABLE_EXTENSION_URL) {

        val variableValue = evaluateRootVariables(fhirPathEngine, extension)

        if (questionnaireResponseItemPathToVariableMap.containsKey(ROOT_VARIABLES)) {
          val variables = questionnaireResponseItemPathToVariableMap[ROOT_VARIABLES]
          updateVariable(variables, extension, variableValue)
        } else {
          questionnaireResponseItemPathToVariableMap[ROOT_VARIABLES] =
            mutableListOf(
              Variable(id = (extension.value as Expression).name, value = variableValue as Type)
            )
        }
      }
    }
  }

  private fun evaluateRootVariables(fhirPathEngine: FHIRPathEngine, extension: Extension): Base? =
    try {
      fhirPathEngine
        .evaluate(
          findRootVariables(extension),
          questionnaireResponse,
          questionnaireResponse,
          questionnaireResponse,
          (extension.value as Expression).expression
        )
        .firstOrNull()
    } catch (exception: PathEngineException) {
      Timber.d("Could not evaluate expression with FHIRPathEngine", exception)
      null
    }

  private fun findRootVariables(extension: Extension): Map<String, Any> {
    val map = mutableMapOf<String, Base>()
    if (questionnaireResponseItemPathToVariableMap.containsKey(ROOT_VARIABLES)) {
      val variables = questionnaireResponseItemPathToVariableMap[ROOT_VARIABLES]
      variables?.forEach {
        if (it.id != (extension.value as Expression).name) {
          map[it.id] = it.value as Type
        }
      }
    }
    return map
  }

  private fun findAncestorVariables(
    extension: Extension,
    questionnaireResponseItem: QuestionnaireResponse.QuestionnaireResponseItemComponent
  ): Map<String, Any> {
    val map = mutableMapOf<String, Base>()

    // check root level variables
    if (questionnaireResponseItemPathToVariableMap.containsKey(ROOT_VARIABLES)) {
      val rootVariables = questionnaireResponseItemPathToVariableMap[ROOT_VARIABLES]
      rootVariables?.forEach {
        if (it.id != (extension.value as Expression).name) {
          map[it.id] = it.value as Type
        }
      }
    }

    // check variables in ancestors
    var path = linkIdToQuestionnaireItemPathMap[questionnaireResponseItem.linkId]
    do {
      if (path?.contains(".") == true) {
        path = path.substringBeforeLast(".")
        val variables = questionnaireResponseItemPathToVariableMap[path]
        variables?.forEach {
          if (it.id != (extension.value as Expression).name) {
            map[it.id] = it.value as Type
          }
        }
      } else {
        path = ""
      }
    } while (path?.isNotEmpty() == true)

    // check current item variables
    val itemVariables =
      questionnaireResponseItemPathToVariableMap[
        linkIdToQuestionnaireItemPathMap[questionnaireResponseItem.linkId]]
    itemVariables?.forEach {
      if (it.id != (extension.value as Expression).name) {
        map[it.id] = it.value as Type
      }
    }

    return map
  }

  private val pageFlow = MutableStateFlow(questionnaire.getInitialPagination())

  private val answerValueSetMap =
    mutableMapOf<String, List<Questionnaire.QuestionnaireItemAnswerOptionComponent>>()

  /**
   * Returns current [QuestionnaireResponse] captured by the UI which includes answers of enabled
   * questions.
   */
  fun getQuestionnaireResponse(): QuestionnaireResponse {
    return questionnaireResponse.copy().apply {
      item = getEnabledResponseItems(this@QuestionnaireViewModel.questionnaire.item, item)
    }
  }

  internal fun goToPreviousPage() {
    pageFlow.value = pageFlow.value!!.previousPage()
  }

  internal fun goToNextPage() {
    pageFlow.value = pageFlow.value!!.nextPage()
  }

  /** [QuestionnaireState] to be displayed in the UI. */
  internal val questionnaireStateFlow: Flow<QuestionnaireState> =
    modificationCount
      .combine(pageFlow) { _, pagination ->
        getQuestionnaireState(
          questionnaireItemList = questionnaire.item,
          questionnaireResponseItemList = questionnaireResponse.item,
          pagination = pagination,
        )
      }
      .stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        initialValue =
          getQuestionnaireState(
            questionnaireItemList = questionnaire.item,
            questionnaireResponseItemList = questionnaireResponse.item,
            pagination = questionnaire.getInitialPagination(),
          )
      )

  @PublishedApi
  internal suspend fun resolveAnswerValueSet(
    uri: String
  ): List<Questionnaire.QuestionnaireItemAnswerOptionComponent> {
    // If cache hit, return it
    if (answerValueSetMap.contains(uri)) {
      return answerValueSetMap[uri]!!
    }

    val options =
      if (uri.startsWith("#")) {
        questionnaire.contained
          .firstOrNull {
            it.id.equals(uri) &&
              it.resourceType == ResourceType.ValueSet &&
              (it as ValueSet).hasExpansion()
          }
          ?.let {
            val valueSet = it as ValueSet
            valueSet.expansion.contains.filterNot { it.abstract || it.inactive }.map { component ->
              Questionnaire.QuestionnaireItemAnswerOptionComponent(
                Coding(component.system, component.code, component.display)
              )
            }
          }
      } else {
        // Ask the client to provide the answers from an external expanded Valueset.
        DataCapture.getConfiguration(getApplication())
          .valueSetResolverExternal
          ?.resolve(uri)
          ?.map { coding -> Questionnaire.QuestionnaireItemAnswerOptionComponent(coding.copy()) }
      }
        ?: emptyList()
    // save it so that we avoid have cache misses.
    answerValueSetMap[uri] = options
    return options
  }

  private fun createLinkIdToQuestionnaireResponseItemMap(
    questionnaireResponseItemList: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>
  ): MutableMap<String, QuestionnaireResponse.QuestionnaireResponseItemComponent> {
    val linkIdToQuestionnaireResponseItemMap =
      questionnaireResponseItemList.map { it.linkId to it }.toMap().toMutableMap()
    for (item in questionnaireResponseItemList) {
      linkIdToQuestionnaireResponseItemMap.putAll(
        createLinkIdToQuestionnaireResponseItemMap(item.item)
      )
      item.answer.forEach {
        linkIdToQuestionnaireResponseItemMap.putAll(
          createLinkIdToQuestionnaireResponseItemMap(it.item)
        )
      }
    }
    return linkIdToQuestionnaireResponseItemMap
  }

  private fun createLinkIdToQuestionnaireItemPathMap(
    questionnaireItemList: List<Questionnaire.QuestionnaireItemComponent>,
    pathPrefix: String = ""
  ): MutableMap<String, String> {

    val linkIdToQuestionnaireItemPathMap =
      questionnaireItemList
        .associate { it.linkId to if (pathPrefix == "") it.linkId else "$pathPrefix.${it.linkId}" }
        .toMutableMap()

    for (item in questionnaireItemList) {
      linkIdToQuestionnaireItemPathMap.putAll(
        createLinkIdToQuestionnaireItemPathMap(
          item.item,
          if (pathPrefix == "") item.linkId else "$pathPrefix.${item.linkId}"
        )
      )
    }
    return linkIdToQuestionnaireItemPathMap
  }

  private fun createQuestionnaireResponseItemPathToVariableMap(
    questionnaireResponseItemList: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>
  ): MutableMap<String, MutableList<Variable>> {
    val questionnaireResponseItemPathToVariableMap =
      questionnaireResponseItemList
        .associate { questionnaireResponseItem ->
          linkIdToQuestionnaireItemPathMap[questionnaireResponseItem.linkId]!! to
            mutableListOf<Variable>()
        }
        .toMutableMap()

    for (item in questionnaireResponseItemList) {
      questionnaireResponseItemPathToVariableMap.putAll(
        createQuestionnaireResponseItemPathToVariableMap(item.item)
      )
    }
    return questionnaireResponseItemPathToVariableMap
  }

  private fun createLinkIdToQuestionnaireItemMap(
    questionnaireItemList: List<Questionnaire.QuestionnaireItemComponent>
  ): Map<String, Questionnaire.QuestionnaireItemComponent> {
    val linkIdToQuestionnaireItemMap =
      questionnaireItemList.map { it.linkId to it }.toMap().toMutableMap()
    for (item in questionnaireItemList) {
      linkIdToQuestionnaireItemMap.putAll(createLinkIdToQuestionnaireItemMap(item.item))
    }
    return linkIdToQuestionnaireItemMap
  }

  /**
   * Traverses through the list of questionnaire items, the list of questionnaire response items and
   * the list of items in the questionnaire response answer list and populates
   * [questionnaireStateFlow] with matching pairs of questionnaire item and questionnaire response
   * item.
   *
   * The traverse is carried out in the two lists in tandem. The two lists should be structurally
   * identical.
   */
  private fun getQuestionnaireState(
    questionnaireItemList: List<Questionnaire.QuestionnaireItemComponent>,
    questionnaireResponseItemList: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
    pagination: QuestionnairePagination?,
  ): QuestionnaireState {
    // TODO(kmost): validate pages before switching between next/prev pages
    var responseIndex = 0
    val items: List<QuestionnaireItemViewItem> =
      questionnaireItemList
        .asSequence()
        .flatMapIndexed { index, questionnaireItem ->
          var questionnaireResponseItem = questionnaireItem.createQuestionnaireResponseItem()

          // If there is an enabled questionnaire response available then we use that. Or else we
          // just use an empty questionnaireResponse Item
          if (responseIndex < questionnaireResponseItemList.size &&
              questionnaireItem.linkId == questionnaireResponseItem.linkId
          ) {
            questionnaireResponseItem = questionnaireResponseItemList[responseIndex]
            responseIndex += 1
          }
          // if the questionnaire is paginated and we're currently working through the paginated
          // groups, make sure that only the current page gets set
          if (pagination != null && pagination.currentPageIndex != index) {
            return@flatMapIndexed emptyList()
          }

          val enabled =
            EnablementEvaluator.evaluate(questionnaireItem) { linkId ->
              linkIdToQuestionnaireResponseItemMap[linkId]
            }

          if (!enabled || questionnaireItem.isHidden) {
            return@flatMapIndexed emptyList()
          }

          listOf(
            QuestionnaireItemViewItem(
              questionnaireItem,
              questionnaireResponseItem,
              { resolveAnswerValueSet(it) }
            ) { questionnaireResponseItemChangedCallback(questionnaireItem.linkId) }
          ) +
            getQuestionnaireState(
                // Nested display item is subtitle text for parent questionnaire item if data type
                // is not group.
                // If nested display item is identified as subtitle text, then do not create
                // questionnaire state for it.
                questionnaireItemList =
                  when (questionnaireItem.type) {
                    Questionnaire.QuestionnaireItemType.GROUP -> questionnaireItem.item
                    else ->
                      questionnaireItem.item.filterNot {
                        it.type == Questionnaire.QuestionnaireItemType.DISPLAY
                      }
                  },
                questionnaireResponseItemList =
                  if (questionnaireResponseItem.answer.isEmpty()) {
                    questionnaireResponseItem.item
                  } else {
                    questionnaireResponseItem.answer.first().item
                  },
                // we're now dealing with nested items, so pagination is no longer a concern
                pagination = null,
              )
              .items
        }
        .toList()
    return QuestionnaireState(items = items, pagination = pagination)
  }

  private fun getEnabledResponseItems(
    questionnaireItemList: List<Questionnaire.QuestionnaireItemComponent>,
    questionnaireResponseItemList: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
  ): List<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
    return questionnaireItemList
      .asSequence()
      .zip(questionnaireResponseItemList.asSequence())
      .filter { (questionnaireItem, _) ->
        EnablementEvaluator.evaluate(questionnaireItem) { linkId ->
          linkIdToQuestionnaireResponseItemMap[linkId] ?: return@evaluate null
        }
      }
      .map { (questionnaireItem, questionnaireResponseItem) ->
        // Nested group items
        questionnaireResponseItem.item =
          getEnabledResponseItems(questionnaireItem.item, questionnaireResponseItem.item)
        // Nested question items
        questionnaireResponseItem.answer.forEach {
          it.item = getEnabledResponseItems(questionnaireItem.item, it.item)
        }
        questionnaireResponseItem
      }
      .toList()
  }

  /**
   * Checks if this questionnaire uses pagination via the "page" extension.
   *
   * If any one group has a "page" extension, it is assumed that the whole questionnaire is a
   * well-formed, paginated questionnaire (eg, each top-level group should be its own page).
   *
   * If this questionnaire uses pagination, returns the [QuestionnairePagination] that you would see
   * when first opening this questionnaire. Otherwise, returns `null`.
   */
  private fun Questionnaire.getInitialPagination(): QuestionnairePagination? {
    val usesPagination =
      item.any { item ->
        item.extension.any { extension ->
          (extension.value as? CodeableConcept)?.coding?.any { coding -> coding.code == "page" } ==
            true
        }
      }
    return if (usesPagination) {
      QuestionnairePagination(
        currentPageIndex = 0,
        lastPageIndex = item.size - 1,
      )
    } else {
      null
    }
  }
}

/** Questionnaire state for the Fragment to consume. */
internal data class QuestionnaireState(
  /** The items that should be currently-rendered into the Fragment. */
  val items: List<QuestionnaireItemViewItem>,
  /** The pagination state of the questionnaire. If `null`, the questionnaire is not paginated. */
  val pagination: QuestionnairePagination?,
)

internal data class QuestionnairePagination(
  val currentPageIndex: Int,
  val lastPageIndex: Int,
)

internal val QuestionnairePagination.hasPreviousPage: Boolean
  get() = currentPageIndex > 0
internal val QuestionnairePagination.hasNextPage: Boolean
  get() = currentPageIndex < lastPageIndex

internal fun QuestionnairePagination.previousPage(): QuestionnairePagination {
  check(hasPreviousPage) { "Can't call previousPage() if hasPreviousPage is false ($this)" }
  return copy(currentPageIndex = currentPageIndex - 1)
}

internal fun QuestionnairePagination.nextPage(): QuestionnairePagination {
  check(hasNextPage) { "Can't call nextPage() if hasNextPage is false ($this)" }
  return copy(currentPageIndex = currentPageIndex + 1)
}

data class Variable(val id: String, var value: IBaseDatatype)

internal const val VARIABLE_EXTENSION_URL = "http://hl7.org/fhir/StructureDefinition/variable"
internal const val ROOT_VARIABLES = "root_variables"
