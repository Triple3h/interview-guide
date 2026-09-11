import { request } from './request';
import { streamSse } from './stream';
import type { InterviewSession } from '../types/interview';

// 向量化状态
export type VectorStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type QuestionGenStatus = 'NONE' | 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  category: string | null;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
  vectorStatus: VectorStatus;
  vectorError: string | null;
  chunkCount: number;
  questionGenStatus: QuestionGenStatus;
  questionGenError: string | null;
}

// 统计信息
export interface KnowledgeBaseStats {
  totalCount: number;
  totalQuestionCount: number;
  totalAccessCount: number;
  completedCount: number;
  processingCount: number;
}

// status：按向量化状态排序（失败 → 处理中 → 待处理 → 已完成）
export type SortOption = 'time' | 'size' | 'access' | 'question' | 'status';

// 批量删除结果：后端逐条删除并隔离失败，返回成功与失败数量
export interface BatchDeleteKnowledgeBaseResult {
  successCount: number;
  failedCount: number;
}

// 分类树节点：可递归，name 为该节点完整路径（如 ai、ai/agent、ai/agent/rag），children 为空表示叶子
export interface CategoryTreeNode {
  name: string;
  children: CategoryTreeNode[];
}

export interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    category: string;
    fileSize: number;
  };
  storage: {
    fileKey: string;
    fileUrl: string;
  };
  duplicate: boolean;
}

// ========== 批量上传批次 ==========

// 批次明细状态：PENDING/PROCESSING/COMPLETED/FAILED 与解析进度联动，DUPLICATE_SKIPPED/REJECTED 为接入阶段终态
export type KbBatchItemStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'DUPLICATE_SKIPPED'
  | 'REJECTED';

// 批次整体状态（派生值）
export type KbBatchStatus = 'PROCESSING' | 'COMPLETED';

export interface KbBatchSummary {
  batchId: number;
  name: string;
  total: number;
  pending: number;
  processing: number;
  completed: number;
  failed: number;
  duplicateSkipped: number;
  rejected: number;
  status: KbBatchStatus;
  createdAt: string;
}

export interface KbBatchItem {
  itemId: number;
  kbId: number | null;
  fileName: string;
  relativePath: string | null;
  category: string | null;
  fileSize: number | null;
  status: KbBatchItemStatus;
  error: string | null;
  createdAt: string;
}

export interface KbBatchDetail extends KbBatchSummary {
  items: KbBatchItem[];
}

export interface CreateKbBatchResponse {
  batchId: number;
  name: string;
}

export interface KbBatchFileUploadResult {
  duplicate: boolean;
  kbId: number | null;
  itemId: number;
  status: string;
}

export interface UploadBatchFileOptions {
  category?: string;
  relativePath?: string;
  name?: string;
}

export interface QueryRequest {
  knowledgeBaseIds: number[];  // 支持多个知识库
  question: string;
}

export interface QueryResponse {
  answer: string;
  knowledgeBaseId: number;
  knowledgeBaseName: string;
}

export type KnowledgeBaseQuestionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | 'STALE';

export interface KnowledgeBaseQuestionFollowUp {
  question: string;
  referenceAnswer?: string | null;
  keyPoints?: string[];
  scoringRubric?: string | null;
}

export interface KnowledgeBaseQuestion {
  id: number;
  knowledgeBaseId: number;
  knowledgeBaseName: string;
  skillId: string;  // 后端兜底字段，固定为 knowledge-base，不再用于业务筛选
  difficulty: string;
  type: string | null;
  category: string;  // 面试方向，由模型生成或用户填写，用于筛选和开始面试
  question: string;
  topicSummary: string | null;
  referenceAnswer: string | null;
  keyPoints: string[];
  scoringRubric: string | null;
  followUps: KnowledgeBaseQuestionFollowUp[];
  sourceContext: string | null;
  status: KnowledgeBaseQuestionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface GenerateKnowledgeBaseQuestionsRequest {
  difficulty?: string;
  questionCount: number;
  followUpCount?: number;
  categoryLimit?: number;
  llmProvider?: string;
}

export interface QuestionGenerationConfig {
  difficulty: string;
  questionCount: number;
  followUpCount: number;
  categoryLimit: number;
  llmProvider: string | null;
}

export interface QuestionGenStatusResponse {
  knowledgeBaseId: number;
  questionGenStatus: QuestionGenStatus;
  questionGenTaskId: string | null;
  questionGenConfig: QuestionGenerationConfig | null;
  savedCount: number;
  skippedCount: number;
  message: string | null;
  error: string | null;
  updatedAt: string | null;
}

export interface SaveKnowledgeBaseQuestionRequest {
  difficulty?: string;
  type?: string | null;
  category: string;
  question: string;
  topicSummary?: string | null;
  referenceAnswer?: string | null;
  keyPoints?: string[];
  scoringRubric?: string | null;
  followUps?: KnowledgeBaseQuestionFollowUp[];
  sourceContext?: string | null;
  status?: KnowledgeBaseQuestionStatus;
}

export interface ListKnowledgeBaseQuestionsParams {
  status?: KnowledgeBaseQuestionStatus | '';
  category?: string;
  difficulty?: string;
  keyword?: string;
}

export interface CategoryCount {
  category: string;
  count: number;
}

export interface CreateKnowledgeBaseInterviewRequest {
  knowledgeBaseId: number;
  category?: string;  // 不传则覆盖全部方向
  difficulty?: string;
  mainQuestionCount: number;
  followUpCount: number;
  llmProvider?: string;
}

export interface InterviewCategoryCapacity {
  category: string;
  availableQuestionCount: number;
}

export interface InterviewFollowUpCapacity {
  followUpCount: number;
  availableQuestionCount: number;
  selectable: boolean;
}

export interface KnowledgeBaseInterviewCapacityResponse {
  knowledgeBaseId: number;
  category: string | null;
  difficulty: string;
  mainQuestionCount: number;
  categories: InterviewCategoryCapacity[];
  followUpOptions: InterviewFollowUpCapacity[];
}

export interface GetKnowledgeBaseInterviewCapacityParams {
  category?: string;
  difficulty: string;
  mainQuestionCount: number;
  followUpCount?: number;
}

export interface BatchGenerateKnowledgeBaseQuestionsRequest {
  knowledgeBaseIds: number[];
  difficulty: string;
  questionCount: number;
  followUpCount?: number;
  categoryLimit: number;
  llmProvider?: string;
}

export interface KnowledgeBaseBatchGenerateResultItem {
  knowledgeBaseId: number;
  submitted: boolean;
  message: string;
}

export interface KnowledgeBaseBatchCapacityRequest {
  knowledgeBaseIds: number[];
  difficulty: string;
  mainQuestionCount: number;
  followUpCount: number;
}

export interface CreateKnowledgeBaseBatchInterviewRequest {
  knowledgeBaseIds: number[];
  difficulty: string;
  mainQuestionCount: number;
  followUpCount: number;
  llmProvider?: string;
}

export const knowledgeBaseApi = {
  /**
   * 上传知识库文件
   */
  async uploadKnowledgeBase(file: File, name?: string, category?: string): Promise<UploadKnowledgeBaseResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (name) {
      formData.append('name', name);
    }
    if (category) {
      formData.append('category', category);
    }
    return request.upload<UploadKnowledgeBaseResponse>('/api/knowledgebase/upload', formData);
  },

  /**
   * 下载知识库文件
   */
  async downloadKnowledgeBase(id: number): Promise<Blob> {
    return request.download(`/api/knowledgebase/${id}/download`);
  },

  // ========== 批量上传与解析进度 ==========

  /**
   * 创建上传批次（一次批量上传作为一个解析任务）
   */
  async createUploadBatch(name?: string): Promise<CreateKbBatchResponse> {
    return request.post<CreateKbBatchResponse>('/api/knowledgebase/upload/batches', {
      name: name?.trim() || null,
    });
  },

  /**
   * 批次内上传单个文件（前端串行调用）
   */
  async uploadBatchFile(
    batchId: number,
    file: File,
    options?: UploadBatchFileOptions
  ): Promise<KbBatchFileUploadResult> {
    const formData = new FormData();
    formData.append('file', file);
    if (options?.category) {
      formData.append('category', options.category);
    }
    if (options?.relativePath) {
      formData.append('relativePath', options.relativePath);
    }
    if (options?.name) {
      formData.append('name', options.name);
    }
    return request.upload<KbBatchFileUploadResult>(
      `/api/knowledgebase/upload/batches/${batchId}/files`,
      formData
    );
  },

  /**
   * 批次列表（含聚合计数，最新在前）
   */
  async listUploadBatches(limit = 20): Promise<KbBatchSummary[]> {
    return request.get<KbBatchSummary[]>(`/api/knowledgebase/upload/batches?limit=${limit}`);
  },

  /**
   * 批次详情（含全部文件明细）
   */
  async getUploadBatch(batchId: number): Promise<KbBatchDetail> {
    return request.get<KbBatchDetail>(`/api/knowledgebase/upload/batches/${batchId}`);
  },

  /**
   * 批量更新知识库分类（category 传 null 表示设为未分类）
   */
  async batchUpdateCategory(ids: number[], category: string | null): Promise<number> {
    return request.put<number>('/api/knowledgebase/batch-category', { ids, category });
  },

  /**
   * 获取所有知识库列表
   */
  async getAllKnowledgeBases(sortBy?: SortOption, vectorStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'): Promise<KnowledgeBaseItem[]> {
    const params = new URLSearchParams();
    if (sortBy) {
      params.append('sortBy', sortBy);
    }
    if (vectorStatus) {
      params.append('vectorStatus', vectorStatus);
    }
    const queryString = params.toString();
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/list${queryString ? `?${queryString}` : ''}`);
  },

  /**
   * 获取知识库详情
   */
  async getKnowledgeBase(id: number): Promise<KnowledgeBaseItem> {
    return request.get<KnowledgeBaseItem>(`/api/knowledgebase/${id}`);
  },

  /**
   * 删除知识库
   */
  async deleteKnowledgeBase(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/${id}`);
  },

  /**
   * 批量删除知识库
   */
  async batchDeleteKnowledgeBases(ids: number[]): Promise<BatchDeleteKnowledgeBaseResult> {
    return request.post<BatchDeleteKnowledgeBaseResult>('/api/knowledgebase/batch-delete', { ids });
  },

  // ========== 分类管理 ==========

  /**
   * 获取所有分类
   */
  async getAllCategories(): Promise<string[]> {
    return request.get<string[]>('/api/knowledgebase/categories');
  },

  /**
   * 获取分类树（一级分类 + 其下二级分类，用于管理页级联筛选）
   */
  async getCategoryTree(): Promise<CategoryTreeNode[]> {
    return request.get<CategoryTreeNode[]>('/api/knowledgebase/category-tree');
  },

  /**
   * 根据分类获取知识库
   * 分类名含斜杠（一级/二级/三级），走查询参数而不是路径变量
   */
  async getByCategory(category: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(
      `/api/knowledgebase/category?category=${encodeURIComponent(category)}`
    );
  },

  /**
   * 获取未分类的知识库
   */
  async getUncategorized(): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>('/api/knowledgebase/uncategorized');
  },

  /**
   * 更新知识库分类
   */
  async updateCategory(id: number, category: string | null): Promise<void> {
    return request.put(`/api/knowledgebase/${id}/category`, { category });
  },

  // ========== 搜索 ==========

  /**
   * 搜索知识库
   */
  async search(keyword: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/search?keyword=${encodeURIComponent(keyword)}`);
  },

  // ========== 统计 ==========

  /**
   * 获取知识库统计信息
   */
  async getStatistics(): Promise<KnowledgeBaseStats> {
    return request.get<KnowledgeBaseStats>('/api/knowledgebase/stats');
  },

  // ========== 向量化管理 ==========

  /**
   * 重新向量化知识库（手动重试）
   */
  async revectorize(id: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/revectorize`);
  },

  // ========== 知识库面试题库 ==========

  async generateQuestions(
    id: number,
    req: GenerateKnowledgeBaseQuestionsRequest
  ): Promise<QuestionGenStatusResponse> {
    return request.post<QuestionGenStatusResponse>(
      `/api/knowledgebase/${id}/questions/generate`,
      req
    );
  },

  async getQuestionGenerationStatus(id: number): Promise<QuestionGenStatusResponse> {
    return request.get<QuestionGenStatusResponse>(
      `/api/knowledgebase/${id}/questions/generation-status`
    );
  },

  async batchQuestionGenerationStatus(
    knowledgeBaseIds: number[]
  ): Promise<QuestionGenStatusResponse[]> {
    return request.post<QuestionGenStatusResponse[]>(
      '/api/knowledgebase/questions/generation-status/batch',
      { knowledgeBaseIds }
    );
  },

  async listQuestions(
    id: number,
    params?: ListKnowledgeBaseQuestionsParams
  ): Promise<KnowledgeBaseQuestion[]> {
    const searchParams = new URLSearchParams();
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value) {
          searchParams.append(key, value);
        }
      });
    }
    const query = searchParams.toString() ? `?${searchParams.toString()}` : '';
    return request.get<KnowledgeBaseQuestion[]>(`/api/knowledgebase/${id}/questions${query}`);
  },

  async listCategories(id: number): Promise<CategoryCount[]> {
    return request.get<CategoryCount[]>(`/api/knowledgebase/${id}/questions/categories`);
  },

  async createQuestion(
    id: number,
    req: SaveKnowledgeBaseQuestionRequest
  ): Promise<KnowledgeBaseQuestion> {
    return request.post<KnowledgeBaseQuestion>(`/api/knowledgebase/${id}/questions`, req);
  },

  async updateQuestion(
    id: number,
    req: Partial<SaveKnowledgeBaseQuestionRequest>
  ): Promise<KnowledgeBaseQuestion> {
    return request.put<KnowledgeBaseQuestion>(`/api/knowledgebase/questions/${id}`, req);
  },

  async updateQuestionStatus(
    id: number,
    status: KnowledgeBaseQuestionStatus
  ): Promise<KnowledgeBaseQuestion> {
    return request.put<KnowledgeBaseQuestion>(`/api/knowledgebase/questions/${id}/status`, { status });
  },

  async deleteQuestion(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/questions/${id}`);
  },

  async createInterviewSession(req: CreateKnowledgeBaseInterviewRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/knowledgebase-interviews/sessions', req);
  },

  async getInterviewCapacity(
    id: number,
    params: GetKnowledgeBaseInterviewCapacityParams
  ): Promise<KnowledgeBaseInterviewCapacityResponse> {
    const searchParams = new URLSearchParams({
      difficulty: params.difficulty,
      mainQuestionCount: String(params.mainQuestionCount),
    });
    if (params.category?.trim()) {
      searchParams.set('category', params.category.trim());
    }
    if (params.followUpCount !== undefined) {
      searchParams.set('followUpCount', String(params.followUpCount));
    }
    return request.get<KnowledgeBaseInterviewCapacityResponse>(
      `/api/knowledgebase/${id}/interview-capacity?${searchParams.toString()}`
    );
  },

  async getBatchInterviewCapacity(
    req: KnowledgeBaseBatchCapacityRequest
  ): Promise<KnowledgeBaseInterviewCapacityResponse> {
    return request.post<KnowledgeBaseInterviewCapacityResponse>(
      '/api/knowledgebase-interviews/batch-capacity',
      req
    );
  },

  async createBatchInterviewSession(
    req: CreateKnowledgeBaseBatchInterviewRequest
  ): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/knowledgebase-interviews/sessions/batch', req);
  },

  async generateQuestionsBatch(
    req: BatchGenerateKnowledgeBaseQuestionsRequest
  ): Promise<KnowledgeBaseBatchGenerateResultItem[]> {
    return request.post<KnowledgeBaseBatchGenerateResultItem[]>(
      '/api/knowledgebase/questions/generate/batch',
      req,
      { timeout: 120000 }
    );
  },

  /**
   * 基于知识库回答问题
   */
  async queryKnowledgeBase(req: QueryRequest): Promise<QueryResponse> {
    return request.post<QueryResponse>('/api/knowledgebase/query', req, {
      timeout: 180000, // 3分钟超时
    });
  },

  /**
   * 基于知识库回答问题（流式SSE）
   * 注意：SSE 使用 fetch API，不走统一的 axios 封装
   */
  async queryKnowledgeBaseStream(
    req: QueryRequest,
    onMessage: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    return streamSse({
      url: '/api/knowledgebase/query/stream',
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(req),
      },
      onMessage,
      onComplete,
      onError,
      parseMode: 'line',
      trimDataPrefixSpace: true,
    });
  },
};
