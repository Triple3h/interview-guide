-- 用户角色与权限（管理端用户管理）
-- 角色：SUPER_ADMIN 超级管理员 / ADMIN 管理员 / USER 普通用户
-- 当前账号（最早创建的学员，线上为 id=1 的「小辉」）升级为超级管理员：系统最高权限，可管理其他用户的角色与权限

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS role VARCHAR(20);
UPDATE app_users SET role = 'USER' WHERE role IS NULL;
ALTER TABLE app_users ALTER COLUMN role SET DEFAULT 'USER';
ALTER TABLE app_users ALTER COLUMN role SET NOT NULL;

-- 存量成员按普通用户起步，仅把当前账号提升为超级管理员
UPDATE app_users
SET role = 'SUPER_ADMIN'
WHERE id = (SELECT MIN(id) FROM app_users);

CREATE INDEX IF NOT EXISTS idx_user_role ON app_users(role);
