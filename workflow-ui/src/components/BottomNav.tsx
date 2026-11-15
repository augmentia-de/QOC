import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

const ITEMS = [
  { to: '/', labelKey: 'nav.dashboard', icon: '📊' },
  { to: '/workflows', labelKey: 'nav.workflows', icon: '📋' },
  { to: '/editor', labelKey: 'nav.graphEditor', icon: '✏️' },
  { to: '/runs', labelKey: 'nav.runs', icon: '▶️' },
  { to: '/settings', labelKey: 'nav.settings', icon: '⚙️' },
];

export default function BottomNav() {
  const { t } = useTranslation();
  const location = useLocation();
  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 flex justify-around bg-gray-800 border-t border-gray-700 py-2 md:hidden safe-area-bottom">
      {ITEMS.map((item) => {
        const active = location.pathname === item.to;
        return (
          <Link
            key={item.to}
            to={item.to}
            className={`flex flex-col items-center text-xs ${active ? 'text-blue-400' : 'text-gray-400'}`}
            style={{ minWidth: 44, minHeight: 44 }}
          >
            <span className="text-lg">{item.icon}</span>
            <span>{t(item.labelKey)}</span>
          </Link>
        );
      })}
    </nav>
  );
}