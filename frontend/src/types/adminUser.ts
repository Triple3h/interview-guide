/** 后台角色编码（与后端 UserRole 对齐） */
export type UserRoleCode = 'SUPER_ADMIN' | 'ADMIN' | 'USER';

/** 账号状态（与后端 app_users.status 对齐） */
export type AccountStatus = 'ACTIVE' | 'DISABLED';

export interface PermissionOption {
  code: string;
  label: string;
  description: string;
}

/** 角色选项：含该角色拥有的权限清单（权限由角色派生，调整角色即调整权限） */
export interface RoleOption {
  code: UserRoleCode;
  label: string;
  description: string;
  permissions: PermissionOption[];
}

export interface AdminUser {
  id: number;
  username: string | null;
  nickname: string;
  avatarEmoji: string | null;
  status: AccountStatus;
  role: UserRoleCode;
  roleLabel: string;
  occupation: string | null;
  learningDirection: string | null;
  currentLevel: string | null;
  learningGoal: string | null;
  createdAt: string | null;
  lastLoginAt: string | null;
}

export interface AdminUserPage {
  items: AdminUser[];
  total: number;
  page: number;
  size: number;
}

export interface AdminUserPageParams {
  page?: number;
  size?: number;
  keyword?: string;
  role?: UserRoleCode | '';
  status?: AccountStatus | '';
}

export interface CreateAdminUserPayload {
  nickname: string;
  username: string;
  password: string;
  role: UserRoleCode;
  status: AccountStatus;
  avatarEmoji?: string;
  occupation?: string;
  learningDirection?: string;
  currentLevel?: string;
}

/** 编辑用户：字段一律显式给出，空串表示清空（后端把 null 视为「不修改」） */
export interface UpdateAdminUserPayload {
  nickname: string;
  username: string;
  role: UserRoleCode;
  status: AccountStatus;
  avatarEmoji: string;
  occupation: string;
  learningDirection: string;
  currentLevel: string;
}
