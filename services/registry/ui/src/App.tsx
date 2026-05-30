import { NavLink, Route, Routes } from 'react-router-dom'
import UsersPage from './pages/Users'
import DevicesPage from './pages/Devices'
import TracksPage from './pages/Tracks'

export default function App() {
  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-title">robo-registry</div>
        <nav>
          <NavLink to="/users" className={({ isActive }) => isActive ? 'active' : ''}>
            Users
          </NavLink>
          <NavLink to="/devices" className={({ isActive }) => isActive ? 'active' : ''}>
            Devices
          </NavLink>
          <NavLink to="/tracks" className={({ isActive }) => isActive ? 'active' : ''}>
            Tracks
          </NavLink>
        </nav>
      </aside>
      <main className="main">
        <Routes>
          <Route path="/" element={<UsersPage />} />
          <Route path="/users" element={<UsersPage />} />
          <Route path="/devices" element={<DevicesPage />} />
          <Route path="/tracks" element={<TracksPage />} />
        </Routes>
      </main>
    </div>
  )
}
