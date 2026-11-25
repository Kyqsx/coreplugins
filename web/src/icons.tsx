/** Ícones SVG próprios (24x24, traço currentColor): herdam cor e tamanho do texto, sem depender de fonte externa. */
const ICONS = {
  'cart-shopping': <><circle cx="9" cy="20" r="1.5" /><circle cx="18" cy="20" r="1.5" /><path d="M2 3h3l2.7 11.4a2 2 0 0 0 2 1.6h7.6a2 2 0 0 0 2-1.5L21 7H6" /></>,
  'cart-plus': <><circle cx="9" cy="20" r="1.5" /><circle cx="18" cy="20" r="1.5" /><path d="M2 3h3l2.7 11.4a2 2 0 0 0 2 1.6h7.6a2 2 0 0 0 2-1.5L21 7H6M13 8.5v4M11 10.5h4" /></>,
  'user': <><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></>,
  'user-plus': <><circle cx="9" cy="8" r="4" /><path d="M2 21a7 7 0 0 1 14 0M19 8v6M16 11h6" /></>,
  'right-to-bracket': <path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 8l4 4-4 4M14 12H3" />,
  'right-from-bracket': <path d="M9 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h4M16 8l4 4-4 4M20 12H9" />,
  'wallet': <><path d="M3 7a2 2 0 0 1 2-2h13v4" /><path d="M3 7v11a2 2 0 0 0 2 2h14a1 1 0 0 0 1-1v-9a1 1 0 0 0-1-1H5a2 2 0 0 1-2-2z" /><circle cx="16" cy="14.5" r=".8" /></>,
  'check': <path d="M4 12.5l5 5L20 6.5" />,
  'trash': <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />,
  'chevron-left': <path d="M15 5l-7 7 7 7" />,
  'chevron-right': <path d="M9 5l7 7-7 7" />,
  'arrow-left': <path d="M20 12H4M10 6l-6 6 6 6" />,
  'arrow-right': <path d="M4 12h16M14 6l6 6-6 6" />,
  'play': <path d="M7 4.5v15l13-7.5z" fill="currentColor" />,
  'xmark': <path d="M6 6l12 12M18 6L6 18" />,
  'minus': <path d="M5 12h14" />,
  'plus': <path d="M12 5v14M5 12h14" />,
  'spinner': <path d="M12 3a9 9 0 1 0 9 9" />,
  'circle-exclamation': <><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5.5M12 16.5v.01" /></>,
  'puzzle-piece': <path d="M10 4a2 2 0 1 1 4 0v2h4v4h-2a2 2 0 1 0 0 4h2v6H4v-4h2a2 2 0 1 0 0-4H4V6h6z" />,
  'ticket': <><path d="M3 8a2 2 0 0 0 0 8v2a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-2a2 2 0 0 1 0-8V6a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1z" /><path d="M14 6.5v11" strokeDasharray="2 2.5" /></>,
  'pix': <><path d="M12 2.5l9.5 9.5-9.5 9.5L2.5 12z" /><path d="M8 12l4-4 4 4-4 4z" /></>,
  'youtube': <><rect x="2" y="5" width="20" height="14" rx="4" /><path d="M10 9v6l5-3z" fill="currentColor" /></>,
} as const;

export type IconName = keyof typeof ICONS;

export function Icon({ name, spin }: { name: IconName; spin?: boolean }) {
  return (
    <svg className={'ico' + (spin ? ' spin' : '')} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{ICONS[name]}</svg>
  );
}
