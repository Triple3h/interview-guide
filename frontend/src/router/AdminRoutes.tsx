import {lazy} from 'react';
import {Navigate, Route, Routes} from 'react-router-dom';
import AdminLayout from '../layouts/AdminLayout';
import RequireAdminAuth from '../auth/RequireAdminAuth';
import AdminLoginPage from '../pages/admin/AdminLoginPage';

// 后台页面（懒加载）
const AdminUsersPage = lazy(() => import('../pages/admin/AdminUsersPage'));

/**
 * 后台路由（/admin/*）：Token 登录页 + 登录守卫 + AdminLayout
 * P4 继续迁入知识库管理 / 题库 / 设置等页面
 */
export default function AdminRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<AdminLoginPage />} />

      <Route element={<RequireAdminAuth />}>
        <Route path="/" element={<AdminLayout />}>
          <Route index element={<Navigate to="/admin/users" replace />} />
          <Route path="users" element={<AdminUsersPage />} />

          {/* 未知后台路径回用户管理 */}
          <Route path="*" element={<Navigate to="/admin/users" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
