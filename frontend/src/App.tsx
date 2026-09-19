import {BrowserRouter, Route, Routes} from 'react-router-dom';
import {lazy, Suspense} from 'react';
import {AuthProvider} from './auth/AuthContext';

// Loading component
const Loading = () => (
  <div className="flex items-center justify-center min-h-[50vh]">
    <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
  </div>
);

// 站点共用一套登录态：学员与管理员同一入口，菜单与后台能力由角色决定
const StudentRoutes = lazy(() => import('./router/StudentRoutes'));

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Suspense fallback={<Loading />}>
          <Routes>
            <Route path="/*" element={<StudentRoutes />} />
          </Routes>
        </Suspense>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
