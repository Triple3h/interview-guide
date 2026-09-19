export interface UserProfile {
  id: number;
  /** 登录账号（/api/auth/me 返回；后台建号时分配） */
  username?: string | null;
  nickname: string;
  avatarEmoji: string | null;
  /** 角色编码：决定菜单与后台能力（SUPER_ADMIN / ADMIN / USER） */
  role?: string;
  /** 角色中文名 */
  roleLabel?: string;
  occupation: string | null;
  learningDirection: string | null;
  learningSkillId: string | null;
  currentLevel: string | null;
  learningGoal: string | null;
  createdAt: string;
}

/** 资料整体提交：字段一律显式给出，空串表示清空（后端把缺省/null 视为「不修改」） */
export interface SaveUserPayload {
  nickname: string;
  avatarEmoji: string;
  occupation: string;
  learningDirection: string;
  learningSkillId: string;
  currentLevel: string;
  learningGoal: string;
}
