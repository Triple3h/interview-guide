package interview.guide.modules.knowledgebase.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseBatchInterviewRequest;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseBatchCapacityRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse.CategoryOption;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse.FollowUpOption;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseInterviewService {

  private static final int MAX_FOLLOW_UP_COUNT = 5;

  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final InterviewSessionService interviewSessionService;
  private final ObjectMapper objectMapper;

  private record QuestionSource(KnowledgeBaseQuestionEntity question,
                                List<String> keyPoints,
                                List<KnowledgeBaseQuestionFollowUpDTO> followUps) {
  }

  public InterviewSessionDTO createSession(CreateKnowledgeBaseInterviewRequest request) {
    knowledgeBaseRepository.findById(request.knowledgeBaseId())
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

    String category = trimToNull(request.category());
    String difficulty = normalizeDifficulty(request.difficulty());
    int mainCount = request.mainQuestionCount();
    int followUpCount = request.followUpCount();

    List<KnowledgeBaseQuestionEntity> raw = selectActiveQuestions(
        request.knowledgeBaseId(), category, difficulty);

    List<QuestionSource> candidates = toQuestionSources(raw).stream()
        .filter(source -> source.followUps().size() >= followUpCount)
        .toList();

    if (candidates.size() < mainCount) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
          buildInsufficientMessage(
              mainCount, candidates.size(), category, difficulty, followUpCount)
      );
    }

    List<QuestionSource> selected = selectBalancedByGroup(
        candidates, mainCount, source -> normalizeGroupKey(source.question().getCategory()));
    List<InterviewQuestionDTO> questions =
        buildQuestions(selected, followUpCount,
            source -> source.question().getCategory());

    log.info("创建知识库面试: kbId={}, category={}, difficulty={}, mainQuestions={}, totalQuestions={}",
        request.knowledgeBaseId(), category, difficulty, mainCount, questions.size());

    // skillId 已不再有业务含义，统一用默认值回填到面试会话表
    return interviewSessionService.createSessionFromQuestions(
        questions,
        request.llmProvider(),
        KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID,
        difficulty,
        request.knowledgeBaseId(),
        category
    );
  }

  public KnowledgeBaseInterviewCapacityResponse getCapacity(
      Long knowledgeBaseId,
      String category,
      String difficulty,
      int mainQuestionCount,
      int followUpCount
  ) {
    knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

    String normalizedCategory = trimToNull(category);
    String normalizedDifficulty = normalizeDifficulty(difficulty);
    int normalizedFollowUpCount = Math.max(0, followUpCount);
    List<QuestionSource> allSources = toQuestionSources(selectActiveQuestions(
        knowledgeBaseId, null, normalizedDifficulty));
    // 方向容量按"满足追问数要求的主问题"统计，供前端预览各方向预计抽题数
    List<QuestionSource> usableSources = allSources.stream()
        .filter(source -> source.followUps().size() >= normalizedFollowUpCount)
        .toList();
    List<QuestionSource> scopedSources = allSources.stream()
        .filter(source -> normalizedCategory == null
            || normalizedCategory.equals(source.question().getCategory()))
        .toList();

    List<CategoryOption> categories = calculateGroupOptions(
        usableSources, source -> source.question().getCategory());
    List<FollowUpOption> followUpOptions = IntStream.rangeClosed(0, MAX_FOLLOW_UP_COUNT)
        .mapToObj(count -> {
          int availableCount = (int) scopedSources.stream()
              .filter(source -> source.followUps().size() >= count)
              .count();
          return new FollowUpOption(
              count,
              availableCount,
              mainQuestionCount > 0 && availableCount >= mainQuestionCount
          );
        })
        .toList();

    return new KnowledgeBaseInterviewCapacityResponse(
        knowledgeBaseId,
        normalizedCategory,
        normalizedDifficulty,
        mainQuestionCount,
        categories,
        followUpOptions
    );
  }

  /**
   * 跨知识库容量：按知识库统计满足追问要求的主问题数，追问档位统计整个选题池。
   */
  public KnowledgeBaseInterviewCapacityResponse getBatchCapacity(
      KnowledgeBaseBatchCapacityRequest request) {
    List<Long> knowledgeBaseIds = request.knowledgeBaseIds().stream().distinct().toList();
    List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(knowledgeBaseIds);
    if (knowledgeBases.size() < knowledgeBaseIds.size()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "部分知识库不存在，请刷新后重试");
    }
    Map<Long, String> kbNames = knowledgeBases.stream()
        .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName,
            (first, second) -> first));

    String normalizedDifficulty = normalizeDifficulty(request.difficulty());
    int normalizedFollowUpCount = Math.max(0, request.followUpCount());
    List<QuestionSource> allSources = toQuestionSources(
        questionRepository.findByKnowledgeBase_IdInAndDifficultyAndStatusOrderByUpdatedAtDesc(
            knowledgeBaseIds, normalizedDifficulty, KnowledgeBaseQuestionStatus.ACTIVE));
    List<QuestionSource> usableSources = allSources.stream()
        .filter(source -> source.followUps().size() >= normalizedFollowUpCount)
        .toList();

    List<CategoryOption> categories = calculateGroupOptions(
        usableSources,
        source -> kbNames.getOrDefault(resolveKbId(source.question()), "未知知识库"));
    List<FollowUpOption> followUpOptions = IntStream.rangeClosed(0, MAX_FOLLOW_UP_COUNT)
        .mapToObj(count -> {
          int availableCount = (int) allSources.stream()
              .filter(source -> source.followUps().size() >= count)
              .count();
          return new FollowUpOption(
              count,
              availableCount,
              request.mainQuestionCount() > 0 && availableCount >= request.mainQuestionCount()
          );
        })
        .toList();

    return new KnowledgeBaseInterviewCapacityResponse(
        null,
        null,
        normalizedDifficulty,
        request.mainQuestionCount(),
        categories,
        followUpOptions
    );
  }

  /**
   * 跨知识库整体开始面试：把每个知识库当作一个分组，按知识库均衡抽题、同库题目连续作答。
   * 题目的 category 统一标记为来源知识库名，便于面试与报告中按库展示。
   */
  public InterviewSessionDTO createBatchSession(CreateKnowledgeBaseBatchInterviewRequest request) {
    List<Long> knowledgeBaseIds = request.knowledgeBaseIds().stream().distinct().toList();
    List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(knowledgeBaseIds);
    if (knowledgeBases.size() < knowledgeBaseIds.size()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "部分知识库不存在，请刷新后重试");
    }
    Map<Long, String> kbNames = knowledgeBases.stream()
        .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName,
            (first, second) -> first));

    String difficulty = normalizeDifficulty(request.difficulty());
    int mainCount = request.mainQuestionCount();
    int followUpCount = request.followUpCount();

    List<QuestionSource> candidates = toQuestionSources(
        questionRepository.findByKnowledgeBase_IdInAndDifficultyAndStatusOrderByUpdatedAtDesc(
            knowledgeBaseIds, difficulty, KnowledgeBaseQuestionStatus.ACTIVE)).stream()
        .filter(source -> source.followUps().size() >= followUpCount)
        .toList();

    if (candidates.size() < mainCount) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
          "需要 " + mainCount + " 道主问题，但所选 " + knowledgeBaseIds.size()
              + " 个知识库在难度=" + difficulty + "、每题至少 " + followUpCount
              + " 个追问的条件下只有 " + candidates.size() + " 道"
      );
    }

    List<QuestionSource> selected = selectBalancedByGroup(
        candidates, mainCount, source -> kbNames.get(resolveKbId(source.question())));
    List<InterviewQuestionDTO> questions = buildQuestions(
        selected, followUpCount,
        source -> kbNames.getOrDefault(resolveKbId(source.question()), "知识库"));

    log.info("创建跨知识库面试: kbCount={}, kbIds={}, difficulty={}, mainQuestions={}, totalQuestions={}",
        knowledgeBaseIds.size(), knowledgeBaseIds, difficulty, mainCount, questions.size());

    return interviewSessionService.createSessionFromQuestions(
        questions,
        request.llmProvider(),
        KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID,
        difficulty,
        null,
        null
    );
  }

  /**
   * 按 category 过滤候选题。
   * category 为空时跨所有方向筛选。
   */
  private List<KnowledgeBaseQuestionEntity> selectActiveQuestions(
      Long knowledgeBaseId, String category, String difficulty) {
    if (category == null) {
      return questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
          knowledgeBaseId, difficulty, KnowledgeBaseQuestionStatus.ACTIVE);
    }
    return questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId, difficulty, category, KnowledgeBaseQuestionStatus.ACTIVE);
  }

  /**
   * 按 groupKey 分组均衡抽题：
   * - 组内洗牌、组间顺序随机，保留抽题的随机性；
   * - 按轮次给每个组分配名额，各组主问题数量尽量均衡（容量不足的组除外）；
   * - 输出按组聚合，保证同组题目在面试中连续作答。
   * 单库面试按方向分组，跨库面试按知识库分组；只有一个组时退化为组内随机抽题。
   */
  private List<QuestionSource> selectBalancedByGroup(
      List<QuestionSource> candidates, int mainCount, Function<QuestionSource, String> groupKey) {
    Map<String, List<QuestionSource>> groups = new LinkedHashMap<>();
    for (QuestionSource source : candidates) {
      groups.computeIfAbsent(normalizeGroupKey(groupKey.apply(source)), key -> new ArrayList<>())
          .add(source);
    }
    List<List<QuestionSource>> shuffledGroups = groups.values().stream()
        .map(group -> {
          List<QuestionSource> shuffled = new ArrayList<>(group);
          Collections.shuffle(shuffled);
          return shuffled;
        })
        .collect(Collectors.toCollection(ArrayList::new));
    Collections.shuffle(shuffledGroups);

    // 轮转分配名额：每轮给每个未耗尽的组取 1 题，直到凑满 mainCount
    List<List<QuestionSource>> picksPerGroup = new ArrayList<>(shuffledGroups.size());
    for (int i = 0; i < shuffledGroups.size(); i += 1) {
      picksPerGroup.add(new ArrayList<>());
    }
    int remaining = mainCount;
    boolean pickedInPass = true;
    while (remaining > 0 && pickedInPass) {
      pickedInPass = false;
      for (int i = 0; i < shuffledGroups.size() && remaining > 0; i += 1) {
        List<QuestionSource> group = shuffledGroups.get(i);
        List<QuestionSource> picks = picksPerGroup.get(i);
        if (picks.size() < group.size()) {
          picks.add(group.get(picks.size()));
          remaining -= 1;
          pickedInPass = true;
        }
      }
    }

    List<QuestionSource> selected = new ArrayList<>();
    for (List<QuestionSource> picks : picksPerGroup) {
      selected.addAll(picks);
    }
    return selected;
  }

  private String normalizeGroupKey(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "未分类" : trimmed;
  }

  /**
   * 解析题目所属知识库 ID：优先关联实体（测试与 save 场景可信），FK 只读列兜底。
   * 与 KnowledgeBaseQuestionService#toDTO 的取值策略保持一致。
   */
  private Long resolveKbId(KnowledgeBaseQuestionEntity question) {
    if (question.getKnowledgeBase() != null && question.getKnowledgeBase().getId() != null) {
      return question.getKnowledgeBase().getId();
    }
    return question.getKnowledgeBaseId();
  }

  private List<InterviewQuestionDTO> buildQuestions(
      List<QuestionSource> selected,
      int followUpCount,
      Function<QuestionSource, String> categoryResolver
  ) {
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    for (QuestionSource source : selected) {
      KnowledgeBaseQuestionEntity entity = source.question();
      String rawCategory = categoryResolver.apply(source);
      int mainIndex = questions.size();
      questions.add(InterviewQuestionDTO.fromQuestionBank(
          mainIndex,
          entity.getQuestion(),
          defaultString(entity.getType(), "KNOWLEDGE_BASE"),
          defaultString(rawCategory, "知识库"),
          entity.getTopicSummary(),
          entity.getReferenceAnswer(),
          source.keyPoints(),
          entity.getScoringRubric(),
          entity.getSourceContext()
      ));

      // 在可用的追问池里随机抽 followUpCount 个，避免每次面试都问同一组追问
      List<KnowledgeBaseQuestionFollowUpDTO> picked = pickFollowUps(source.followUps(), followUpCount);
      for (KnowledgeBaseQuestionFollowUpDTO followUp : picked) {
        questions.add(new InterviewQuestionDTO(
            questions.size(),
            followUp.question(),
            defaultString(entity.getType(), "KNOWLEDGE_BASE"),
            defaultString(rawCategory, "知识库追问"),
            entity.getTopicSummary(),
            null,
            null,
            null,
            true,
            mainIndex,
            followUp.referenceAnswer(),
            followUp.keyPoints(),
            followUp.scoringRubric(),
            entity.getSourceContext()
        ));
      }
    }
    return questions;
  }

  /**
   * 在追问池里随机抽取严格的 count 个，池容量不足时拒绝继续组装。
   * 使用 Fisher-Yates 局部洗牌，避免改动原列表顺序。
   */
  private List<KnowledgeBaseQuestionFollowUpDTO> pickFollowUps(
      List<KnowledgeBaseQuestionFollowUpDTO> pool, int count) {
    if (count <= 0) {
      return List.of();
    }
    if (pool == null || pool.size() < count) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
          "追问池在组装面试时发生变化，无法严格抽取 " + count + " 个追问"
      );
    }
    int n = count;
    if (n == pool.size()) {
      return new ArrayList<>(pool);
    }
    List<KnowledgeBaseQuestionFollowUpDTO> copy = new ArrayList<>(pool);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < n; i += 1) {
      int j = random.nextInt(i, copy.size());
      KnowledgeBaseQuestionFollowUpDTO tmp = copy.get(i);
      copy.set(i, copy.get(j));
      copy.set(j, tmp);
    }
    return copy.subList(0, n);
  }

  private List<QuestionSource> toQuestionSources(List<KnowledgeBaseQuestionEntity> questions) {
    return questions.stream()
        .map(question -> new QuestionSource(
            question,
            readStringList(question.getKeyPointsJson()),
            readUsableFollowUps(question.getFollowUpsJson())
        ))
        .toList();
  }

  private List<CategoryOption> calculateGroupOptions(
      List<QuestionSource> sources, Function<QuestionSource, String> groupKey) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (QuestionSource source : sources) {
      String key = trimToNull(groupKey.apply(source));
      if (key != null) {
        counts.merge(key, 1, Integer::sum);
      }
    }
    return counts.entrySet().stream()
        .map(entry -> new CategoryOption(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(CategoryOption::availableQuestionCount).reversed()
            .thenComparing(CategoryOption::category))
        .toList();
  }

  private String buildInsufficientMessage(
      int requiredCount,
      int availableCount,
      String category,
      String difficulty,
      int followUpCount
  ) {
    String direction = category == null ? "全部方向" : category;
    return "需要 " + requiredCount + " 道主问题，但只有 " + availableCount
        + " 道同时满足：方向=" + direction
        + "、难度=" + difficulty
        + "、每题至少 " + followUpCount + " 个追问";
  }

  private List<String> readStringList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析题目要点失败: {}", e.getMessage());
      return List.of();
    }
  }

  private List<KnowledgeBaseQuestionFollowUpDTO> readUsableFollowUps(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> followUps =
          objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
      if (followUps == null) {
        return List.of();
      }
      return followUps.stream()
          .filter(followUp -> followUp != null
              && followUp.question() != null
              && !followUp.question().isBlank())
          .map(followUp -> new KnowledgeBaseQuestionFollowUpDTO(
              followUp.question().trim(),
              followUp.referenceAnswer(),
              followUp.keyPoints(),
              followUp.scoringRubric()
          ))
          .toList();
    } catch (JacksonException e) {
      log.warn("解析追问失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String defaultString(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }
}
