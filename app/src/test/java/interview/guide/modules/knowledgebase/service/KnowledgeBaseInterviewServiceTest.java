package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseBatchInterviewRequest;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseBatchCapacityRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseInterviewServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private KnowledgeBaseQuestionRepository questionRepository;

  @Mock
  private InterviewSessionService interviewSessionService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("启用题目不足时拒绝创建知识库面试")
  void shouldRejectWhenActiveQuestionsAreInsufficient() {
    KnowledgeBaseInterviewService service = newService();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of());

    CreateKnowledgeBaseInterviewRequest request =
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 0, "");

    assertThatThrownBy(() -> service.createSession(request))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT.getCode());
          assertThat(exception.getMessage()).contains("需要 1 道主问题", "只有 0 道");
        });
  }

  @Test
  @DisplayName("不传 category 时跨全部方向抽题")
  @SuppressWarnings("unchecked")
  void shouldCreateSessionAcrossAllCategoriesWhenCategoryIsNull() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    InterviewSessionDTO expected =
        new InterviewSessionDTO("session1", "", 2, 0, List.of(), SessionStatus.CREATED, 1L, null);
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(expected);

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));
    assertThat(actual).isSameAs(expected);
    assertThat(captor.getValue()).hasSize(2);
    assertThat(captor.getValue().get(0).isFollowUp()).isFalse();
    assertThat(captor.getValue().get(1).isFollowUp()).isTrue();
    assertThat(captor.getValue().get(0).category()).isEqualTo("Redis");
  }

  @Test
  @DisplayName("传 category 时按方向过滤候选题")
  @SuppressWarnings("unchecked")
  void shouldFilterByCategoryWhenCategoryProvided() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("Redis")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "Redis"));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "Redis", "mid", 1, 0, ""));

    // 验证走的是按 category 过滤的查询方法，而不是全量方法
    verify(questionRepository).findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE);
  }

  @Test
  @DisplayName("创建知识库面试时将 category 规范化后传递到面试会话")
  @SuppressWarnings("unchecked")
  void shouldPassNormalizedCategoryToSession() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "MySQL", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "MySQL"));

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "  MySQL  ", "mid", 1, 0, ""));

    verify(interviewSessionService).createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL"));
    assertThat(actual.interviewCategory()).isEqualTo("MySQL");
  }

  @Test
  @DisplayName("followUpCount 小于追问池时随机抽取指定数量的追问")
  @SuppressWarnings("unchecked")
  void shouldPickFollowUpsRandomlyWhenPoolLargerThanCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithThreeFollowUps();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 3, 0, List.of(), SessionStatus.CREATED, 1L, null));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 2, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));

    List<InterviewQuestionDTO> built = captor.getValue();
    assertThat(built).hasSize(3);
    List<String> followUpQuestions = built.subList(1, 3).stream()
        .map(InterviewQuestionDTO::question).toList();
    assertThat(followUpQuestions).hasSize(2);
    assertThat(followUpQuestions).doesNotHaveDuplicates();
    assertThat(followUpQuestions).isSubsetOf("追问1", "追问2", "追问3");
  }

  @Test
  @DisplayName("追问池不足严格数量时使用专用错误码拒绝创建面试")
  @SuppressWarnings("unchecked")
  void shouldRejectWhenFollowUpPoolIsSmallerThanRequestedCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));

    assertThatThrownBy(() -> service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 3, "")))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT.getCode());
          assertThat(exception.getMessage()).contains("每题至少 3 个追问");
        });

    verify(interviewSessionService, never()).createSessionFromQuestions(
        any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("容量查询按非空追问题干统计严格可用题数")
  void shouldCalculateStrictCapacityByUsableFollowUpCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity noFollowUp = questionWithFollowUps("Redis", List.of());
    KnowledgeBaseQuestionEntity oneFollowUp = questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("   ")
    ));
    KnowledgeBaseQuestionEntity threeFollowUps = questionWithFollowUps("MySQL", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2"),
        new KnowledgeBaseQuestionFollowUpDTO("追问3")
    ));
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE))
        .thenReturn(List.of(noFollowUp, oneFollowUp, threeFollowUps));

    KnowledgeBaseInterviewCapacityResponse response =
        service.getCapacity(1L, null, "mid", 2, 0);

    assertThat(response.followUpOptions())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::followUpCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::availableQuestionCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::selectable
        )
        .contains(
            org.assertj.core.groups.Tuple.tuple(0, 3, true),
            org.assertj.core.groups.Tuple.tuple(1, 2, true),
            org.assertj.core.groups.Tuple.tuple(2, 1, false),
            org.assertj.core.groups.Tuple.tuple(3, 1, false)
        );
    assertThat(response.categories())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::category,
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::availableQuestionCount
        )
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Redis", 2),
            org.assertj.core.groups.Tuple.tuple("MySQL", 1)
        );
  }

  @Test
  @DisplayName("容量查询按方向过滤追问选项但保留全部方向统计")
  void shouldFilterCapacityByCategoryAndKeepCategoryOptions() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity redis = questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2")
    ));
    KnowledgeBaseQuestionEntity mysql = questionWithFollowUps("MySQL", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1")
    ));
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(redis, mysql));

    KnowledgeBaseInterviewCapacityResponse response =
        service.getCapacity(1L, " Redis ", "mid", 1, 0);

    assertThat(response.category()).isEqualTo("Redis");
    assertThat(response.followUpOptions())
        .filteredOn(option -> option.followUpCount() == 2)
        .singleElement()
        .satisfies(option -> {
          assertThat(option.availableQuestionCount()).isEqualTo(1);
          assertThat(option.selectable()).isTrue();
        });
    assertThat(response.categories()).hasSize(2);
  }

  @Test
  @DisplayName("全部方向抽题时按方向均衡分配名额且同方向连续作答")
  @SuppressWarnings("unchecked")
  void shouldSelectBalancedQuestionsGroupedByCategoryWhenCategoryIsNull() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    List<KnowledgeBaseQuestionEntity> candidates = new ArrayList<>();
    candidates.addAll(questionsOfCategory("Redis", 3));
    candidates.addAll(questionsOfCategory("MySQL", 3));
    candidates.addAll(questionsOfCategory("JVM", 3));
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(candidates);
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 8, 0, List.of(), SessionStatus.CREATED, 1L, null));

    service.createSession(new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 4, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));

    List<String> mainCategories = captor.getValue().stream()
        .filter(question -> !question.isFollowUp())
        .map(InterviewQuestionDTO::category)
        .toList();
    assertThat(mainCategories).hasSize(4);
    // 同方向题目连续：分类出现的连续段数等于方向数
    int categoryRuns = 1;
    for (int i = 1; i < mainCategories.size(); i += 1) {
      if (!mainCategories.get(i).equals(mainCategories.get(i - 1))) {
        categoryRuns += 1;
      }
    }
    assertThat(categoryRuns).isEqualTo(3);
    // 各方向名额均衡：每方向 1~2 题，最大差距不超过 1
    Map<String, Long> counts = mainCategories.stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(counts).hasSize(3);
    assertThat(Collections.max(counts.values()) - Collections.min(counts.values())).isLessThanOrEqualTo(1);
  }

  @Test
  @DisplayName("方向题量不足时名额向其他方向倾斜但仍按方向连续")
  @SuppressWarnings("unchecked")
  void shouldGiveSurplusQuotaToCategoriesWithEnoughQuestions() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    List<KnowledgeBaseQuestionEntity> candidates = new ArrayList<>();
    candidates.addAll(questionsOfCategory("Redis", 4));
    candidates.addAll(questionsOfCategory("MySQL", 1));
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(candidates);
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 8, 0, List.of(), SessionStatus.CREATED, 1L, null));

    service.createSession(new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 4, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));

    List<String> mainCategories = captor.getValue().stream()
        .filter(question -> !question.isFollowUp())
        .map(InterviewQuestionDTO::category)
        .toList();
    assertThat(mainCategories).hasSize(4);
    assertThat(Collections.frequency(mainCategories, "Redis")).isEqualTo(3);
    assertThat(Collections.frequency(mainCategories, "MySQL")).isEqualTo(1);
    // MySQL 只能出现在队首或队尾，保证同方向连续
    assertThat(mainCategories.get(1)).isEqualTo("Redis");
    assertThat(mainCategories.get(2)).isEqualTo("Redis");
  }

  @Test
  @DisplayName("容量查询按追问数过滤方向可用题数但不影响追问档位统计")
  void shouldFilterCategoryCapacityByFollowUpCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity noFollowUp = questionWithFollowUps("Redis", List.of());
    KnowledgeBaseQuestionEntity twoFollowUps = questionWithFollowUps("MySQL", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2")
    ));
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(noFollowUp, twoFollowUps));

    KnowledgeBaseInterviewCapacityResponse response =
        service.getCapacity(1L, null, "mid", 5, 1);

    assertThat(response.categories())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::category,
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::availableQuestionCount
        )
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("MySQL", 1)
        );
    assertThat(response.followUpOptions())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::followUpCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::availableQuestionCount
        )
        .contains(
            org.assertj.core.groups.Tuple.tuple(0, 2),
            org.assertj.core.groups.Tuple.tuple(1, 1)
        );
  }

  @Test
  @DisplayName("跨知识库面试按知识库均衡抽题且题目分类标记为知识库名")
  @SuppressWarnings("unchecked")
  void shouldCreateBatchSessionBalancedByKnowledgeBase() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseEntity kb1 = knowledgeBase(1L, "order by 是怎么工作的");
    KnowledgeBaseEntity kb2 = knowledgeBase(2L, "幻读是什么");
    KnowledgeBaseEntity kb3 = knowledgeBase(3L, "索引选择");
    List<KnowledgeBaseQuestionEntity> candidates = new ArrayList<>();
    candidates.addAll(questionsOfKb(kb1, 3));
    candidates.addAll(questionsOfKb(kb2, 3));
    candidates.addAll(questionsOfKb(kb3, 3));
    when(knowledgeBaseRepository.findAllById(List.of(1L, 2L, 3L)))
        .thenReturn(List.of(kb1, kb2, kb3));
    when(questionRepository.findByKnowledgeBase_IdInAndDifficultyAndStatusOrderByUpdatedAtDesc(
        List.of(1L, 2L, 3L), "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(candidates);
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(null), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 8, 0, List.of(), SessionStatus.CREATED, null, null));

    service.createBatchSession(
        new CreateKnowledgeBaseBatchInterviewRequest(List.of(1L, 2L, 3L), "mid", 4, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"),
        eq(null), eq(null));

    List<String> mainCategories = captor.getValue().stream()
        .filter(question -> !question.isFollowUp())
        .map(InterviewQuestionDTO::category)
        .toList();
    assertThat(mainCategories).hasSize(4);
    // 每个知识库的名字都出现在题目分类中，且各库名额均衡（1~2 题）
    Map<String, Long> counts = mainCategories.stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    assertThat(counts).containsKeys("order by 是怎么工作的", "幻读是什么", "索引选择");
    assertThat(Collections.max(counts.values()) - Collections.min(counts.values())).isLessThanOrEqualTo(1);
    // 同一知识库的题目连续出现
    int categoryRuns = 1;
    for (int i = 1; i < mainCategories.size(); i += 1) {
      if (!mainCategories.get(i).equals(mainCategories.get(i - 1))) {
        categoryRuns += 1;
      }
    }
    assertThat(categoryRuns).isEqualTo(3);
  }

  @Test
  @DisplayName("跨知识库容量查询按知识库统计且支持追问过滤")
  void shouldCalculateBatchCapacityByKnowledgeBase() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseEntity kb1 = knowledgeBase(1L, "知识库A");
    KnowledgeBaseEntity kb2 = knowledgeBase(2L, "知识库B");
    KnowledgeBaseQuestionEntity noFollowUp = questionWithFollowUps("任意", List.of());
    noFollowUp.setKnowledgeBase(kb1);
    KnowledgeBaseQuestionEntity twoFollowUps = questionWithFollowUps("任意", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2")
    ));
    twoFollowUps.setKnowledgeBase(kb2);
    when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(kb1, kb2));
    when(questionRepository.findByKnowledgeBase_IdInAndDifficultyAndStatusOrderByUpdatedAtDesc(
        List.of(1L, 2L), "mid", KnowledgeBaseQuestionStatus.ACTIVE))
        .thenReturn(List.of(noFollowUp, twoFollowUps));

    KnowledgeBaseInterviewCapacityResponse response = service.getBatchCapacity(
        new KnowledgeBaseBatchCapacityRequest(List.of(1L, 2L), "mid", 5, 1));

    assertThat(response.categories())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::category,
            KnowledgeBaseInterviewCapacityResponse.CategoryOption::availableQuestionCount
        )
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("知识库B", 1)
        );
    assertThat(response.followUpOptions())
        .extracting(
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::followUpCount,
            KnowledgeBaseInterviewCapacityResponse.FollowUpOption::availableQuestionCount
        )
        .contains(
            org.assertj.core.groups.Tuple.tuple(0, 2),
            org.assertj.core.groups.Tuple.tuple(1, 1)
        );
  }

  @Test
  @DisplayName("跨知识库面试所选知识库不存在时拒绝创建")
  void shouldRejectBatchSessionWhenKnowledgeBaseMissing() {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseEntity kb1 = knowledgeBase(1L, "知识库A");
    when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(kb1));

    assertThatThrownBy(() -> service.createBatchSession(
        new CreateKnowledgeBaseBatchInterviewRequest(List.of(1L, 2L), "mid", 3, 1, "")))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
          assertThat(exception.getMessage()).contains("部分知识库不存在");
        });
  }

  private KnowledgeBaseInterviewService newService() {
    return new KnowledgeBaseInterviewService(
        knowledgeBaseRepository,
        questionRepository,
        interviewSessionService,
        objectMapper
    );
  }

  private KnowledgeBaseQuestionEntity questionWithFollowUp() throws Exception {
    KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
    entity.setQuestion("主问题");
    entity.setType("REDIS");
    entity.setCategory("Redis");
    entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setReferenceAnswer("参考答案");
    entity.setKeyPointsJson(objectMapper.writeValueAsString(List.of("要点")));
    entity.setScoringRubric("评分规则");
    entity.setFollowUpsJson(objectMapper.writeValueAsString(List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问", "追问参考答案", List.of("追问要点"), "追问评分规则")
    )));
    return entity;
  }

  private KnowledgeBaseQuestionEntity questionWithThreeFollowUps() throws Exception {
    return questionWithFollowUps("Redis", List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1", "答1", List.of(), "规则1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2", "答2", List.of(), "规则2"),
        new KnowledgeBaseQuestionFollowUpDTO("追问3", "答3", List.of(), "规则3")
    ));
  }

  private KnowledgeBaseQuestionEntity questionWithFollowUps(
      String category,
      List<KnowledgeBaseQuestionFollowUpDTO> followUps
  ) throws Exception {
    KnowledgeBaseQuestionEntity entity = questionWithFollowUp();
    entity.setCategory(category);
    entity.setFollowUpsJson(objectMapper.writeValueAsString(followUps));
    return entity;
  }

  private KnowledgeBaseEntity knowledgeBase(Long id, String name) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(id);
    entity.setName(name);
    return entity;
  }

  private List<KnowledgeBaseQuestionEntity> questionsOfCategory(String category, int count)
      throws Exception {
    List<KnowledgeBaseQuestionEntity> list = new ArrayList<>();
    for (int i = 0; i < count; i += 1) {
      KnowledgeBaseQuestionEntity entity = questionWithFollowUps(category, List.of(
          new KnowledgeBaseQuestionFollowUpDTO("追问-" + category + "-" + i)
      ));
      entity.setQuestion("主问题-" + category + "-" + i);
      list.add(entity);
    }
    return list;
  }

  private List<KnowledgeBaseQuestionEntity> questionsOfKb(KnowledgeBaseEntity kb, int count)
      throws Exception {
    List<KnowledgeBaseQuestionEntity> list = new ArrayList<>();
    for (int i = 0; i < count; i += 1) {
      KnowledgeBaseQuestionEntity entity = questionWithFollowUps("题库方向", List.of(
          new KnowledgeBaseQuestionFollowUpDTO("追问-" + kb.getId() + "-" + i)
      ));
      entity.setQuestion("主问题-" + kb.getId() + "-" + i);
      entity.setKnowledgeBase(kb);
      list.add(entity);
    }
    return list;
  }
}
