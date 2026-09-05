package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.CreateKbBatchRequest;
import interview.guide.modules.knowledgebase.model.CreateKbBatchResponse;
import interview.guide.modules.knowledgebase.model.KbBatchDetailDTO;
import interview.guide.modules.knowledgebase.model.KbBatchFileUploadResult;
import interview.guide.modules.knowledgebase.model.KbBatchListItemDTO;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseBatchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库批量上传控制器
 * 批次创建、批次内逐文件上传、批次解析进度查询
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库批量上传", description = "批量上传知识库文件与批次解析进度")
public class KnowledgeBaseBatchController {

    private final KnowledgeBaseBatchService batchService;

    /**
     * 创建上传批次
     */
    @PostMapping("/api/knowledgebase/upload/batches")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<CreateKbBatchResponse> createBatch(@Valid @RequestBody(required = false) CreateKbBatchRequest request) {
        String name = request != null ? request.name() : null;
        return Result.success(batchService.createBatch(name));
    }

    /**
     * 批次内上传单个文件
     * 前端逐个上传，服务端校验、去重、存储后入队异步解析
     */
    @PostMapping(value = "/api/knowledgebase/upload/batches/{batchId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<KbBatchFileUploadResult> uploadBatchFile(
            @PathVariable Long batchId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "relativePath", required = false) String relativePath,
            @RequestParam(value = "name", required = false) String name) {
        return Result.success(batchService.uploadBatchFile(batchId, file, category, relativePath, name));
    }

    /**
     * 批次列表（含聚合计数，最新在前）
     */
    @GetMapping("/api/knowledgebase/upload/batches")
    public Result<List<KbBatchListItemDTO>> listBatches(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(batchService.listBatches(limit));
    }

    /**
     * 批次详情（含全部文件明细）
     */
    @GetMapping("/api/knowledgebase/upload/batches/{batchId}")
    public Result<KbBatchDetailDTO> getBatchDetail(@PathVariable Long batchId) {
        return Result.success(batchService.getBatchDetail(batchId));
    }
}
