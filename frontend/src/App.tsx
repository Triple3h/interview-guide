import {BrowserRouter, Route, Routes} from 'react-router-dom';
import {lazy, Suspense} from 'react';
import {AuthProvider} from './auth/AuthContext';
import {AdminAuthProvider} from './auth/AdminAuthContext';

// Loading component
const Loading = () => (
  <div className="flex items-center justify-center min-h-[50vh]">
    <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
  </div>
);

// 双端路由：/admin/** 为后台（PC），其余为学员端（移动优先）
const AdminRoutes = lazy(() => import('./router/AdminRoutes'));
const StudentRoutes = lazy(() => import('./router/StudentRoutes'));

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AdminAuthProvider>
          <Suspense fallback={<Loading />}>
            <Routes>
              <Route path="/admin/*" element={<AdminRoutes />} />
              <Route path="/*" element={<StudentRoutes />} />
            </Routes>
          </Suspense>
        </AdminAuthProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
