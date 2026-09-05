package interview.guide.modules.learning.controller;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.learning.model.LearningPlanDTO.CreatePlanItemRequest;
import interview.guide.modules.learning.model.LearningPlanDTO.PlanItemResponse;
import interview.guide.modules.learning.model.LearningPlanDTO.UpdatePlanItemRequest;
import interview.guide.modules.learning.model.LearningPlanDTO.UpdatePlanStatusRequest;
import interview.guide.modules.learning.service.LearningPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学习计划（Agent 商定后固化，也可手动维护）
 */
@Tag(name = "学习计划", description = "与 AI 商定的学习计划条目，支持查看、状态流转与编辑")
@Slf4j
@RestController
@RequestMapping("/api/learning/plans")
@RequiredArgsConstructor
public class LearningPlanController {

    private final LearningPlanService planService;

    /**
     * 我的计划列表
     */
    @GetMapping
    public Result<List<PlanItemResponse>> list(@LoginUser CurrentUser currentUser) {
        return Result.success(planService.list(currentUser.id()));
    }

    @PostMapping
    public Result<PlanItemResponse> create(@Valid @RequestBody CreatePlanItemRequest request,
                                           @LoginUser CurrentUser currentUser) {
        return Result.success(planService.create(currentUser.id(), request));
    }

    @PutMapping("/{id}")
    public Result<PlanItemResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdatePlanItemRequest request,
                                           @LoginUser CurrentUser currentUser) {
        return Result.success(planService.update(currentUser.id(), id, request));
    }

    @PutMapping("/{id}/status")
    public Result<PlanItemResponse> updateStatus(@PathVariable Long id,
                                                 @Valid @RequestBody UpdatePlanStatusRequest request,
                                                 @LoginUser CurrentUser currentUser) {
        return Result.success(planService.updateStatus(currentUser.id(), id, request.status()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @LoginUser CurrentUser currentUser) {
        planService.delete(currentUser.id(), id);
        return Result.success(null);
    }
}
