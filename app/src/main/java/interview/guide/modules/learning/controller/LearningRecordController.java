package interview.guide.modules.learning.controller;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.learning.model.LearningRecordDTO.CreateRecordRequest;
import interview.guide.modules.learning.model.LearningRecordDTO.LearningRecordResponse;
import interview.guide.modules.learning.model.LearningRecordDTO.UpdateRecordRequest;
import interview.guide.modules.learning.service.LearningRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学习台账（知识台账）
 */
@Tag(name = "学习台账", description = "学过的知识点台账，支持查看、编辑与搜索")
@Slf4j
@RestController
@RequestMapping("/api/learning/records")
@RequiredArgsConstructor
public class LearningRecordController {

    private final LearningRecordService recordService;

    /**
     * 我的台账列表（keyword 可选）
     */
    @GetMapping
    public Result<List<LearningRecordResponse>> list(@LoginUser CurrentUser currentUser,
                                                     @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(recordService.list(currentUser.id(), keyword));
    }

    @PostMapping
    public Result<LearningRecordResponse> create(@Valid @RequestBody CreateRecordRequest request,
                                                 @LoginUser CurrentUser currentUser) {
        return Result.success(recordService.create(currentUser.id(), request));
    }

    @PutMapping("/{id}")
    public Result<LearningRecordResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateRecordRequest request,
                                                 @LoginUser CurrentUser currentUser) {
        return Result.success(recordService.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @LoginUser CurrentUser currentUser) {
        recordService.delete(currentUser.id(), id);
        return Result.success(null);
    }
}
