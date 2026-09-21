package interview.guide.modules.learning.controller;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.learning.model.LearningMemoryDTO.CreateMemoryRequest;
import interview.guide.modules.learning.model.LearningMemoryDTO.LearningMemoryResponse;
import interview.guide.modules.learning.model.LearningMemoryDTO.UpdateMemoryRequest;
import interview.guide.modules.learning.service.LearningMemoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人记忆（偏好、提问、易错点等，按学员隔离）
 */
@Tag(name = "个人记忆", description = "跨会话个人记忆，支持查看、编辑与搜索")
@Slf4j
@RestController
@RequestMapping("/api/learning/memories")
@RequiredArgsConstructor
public class LearningMemoryController {

    private final LearningMemoryService memoryService;

    @GetMapping
    public Result<List<LearningMemoryResponse>> list(@LoginUser CurrentUser currentUser,
                                                     @RequestParam(value = "keyword", required = false) String keyword,
                                                     @RequestParam(value = "kind", required = false) String kind) {
        return Result.success(memoryService.list(currentUser.id(), keyword, kind));
    }

    @PostMapping
    public Result<LearningMemoryResponse> create(@Valid @RequestBody CreateMemoryRequest request,
                                                 @LoginUser CurrentUser currentUser) {
        return Result.success(memoryService.create(currentUser.id(), request));
    }

    @PutMapping("/{id}")
    public Result<LearningMemoryResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateMemoryRequest request,
                                                 @LoginUser CurrentUser currentUser) {
        return Result.success(memoryService.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @LoginUser CurrentUser currentUser) {
        memoryService.delete(currentUser.id(), id);
        return Result.success(null);
    }
}
