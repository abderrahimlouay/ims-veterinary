import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { NavLink, useLocation } from "react-router";
import { NavIcon } from "../../components/NavIcon";
import {
  groupOf,
  NAV_GROUPS,
  NAV_ITEMS,
  visibleNavItems,
  type NavGroup,
  type NavItem,
} from "../navigation";

/** The sections the user opened, kept on this device (a preference, not data). */
const OPEN_KEY = "ims.nav.open";

function storedOpen(): NavGroup[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(OPEN_KEY) ?? "[]");
    return Array.isArray(value)
      ? value.filter((g): g is NavGroup =>
          (NAV_GROUPS as readonly string[]).includes(g),
        )
      : [];
  } catch {
    return [];
  }
}

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-2.5 px-3 py-1.5 rounded-ctl text-sm ${isActive ? "bg-brand/10 text-brand font-medium" : "text-ink hover:bg-bg"}`;

function Item({ item }: { item: NavItem }) {
  const { t } = useTranslation();
  return (
    <NavLink to={item.to} end={item.to === "/"} className={linkClass}>
      <NavIcon name={item.icon} />
      <span className="truncate">{t(item.labelKey)}</span>
    </NavLink>
  );
}

/**
 * The side navigation (frontend.md §3): the entries the user's permissions allow, the home and the counter on top,
 * the others in collapsible sections. The section of the current page opens by itself; the sections a user opens stay
 * open on this device.
 */
export function SideNav({ permissions }: { permissions: readonly string[] }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const baseId = useId();
  const current = groupOf(NAV_ITEMS, pathname);
  const [open, setOpen] = useState<NavGroup[]>(() => {
    const stored = storedOpen();
    return current === undefined || stored.includes(current)
      ? stored
      : [...stored, current];
  });
  const [shownFor, setShownFor] = useState(current);
  if (current !== shownFor) {
    // Another page: its section opens (adjusting state while rendering, not in an effect).
    setShownFor(current);
    if (current !== undefined && !open.includes(current)) {
      setOpen([...open, current]);
    }
  }
  const visible = visibleNavItems(NAV_ITEMS, permissions);

  useEffect(() => {
    try {
      localStorage.setItem(OPEN_KEY, JSON.stringify(open));
    } catch {
      // A device refusing storage only forgets the open sections.
    }
  }, [open]);

  const toggle = (group: NavGroup) =>
    setOpen((groups) =>
      groups.includes(group)
        ? groups.filter((g) => g !== group)
        : [...groups, group],
    );

  return (
    <aside className="w-60 shrink-0 bg-surface border-e border-line">
      <nav
        aria-label={t("nav.label")}
        className="sticky top-0 max-h-screen flex flex-col"
      >
        <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-0.5">
          {visible
            .filter((item) => item.group === undefined)
            .map((item) => (
              <Item key={item.to} item={item} />
            ))}
          {NAV_GROUPS.map((group) => {
            const items = visible.filter((item) => item.group === group);
            if (items.length === 0) {
              return null;
            }
            const expanded = open.includes(group);
            const listId = `${baseId}-${group}`;
            return (
              <div key={group} className="mt-3">
                <button
                  type="button"
                  aria-expanded={expanded}
                  aria-controls={listId}
                  onClick={() => toggle(group)}
                  className="w-full flex items-center justify-between px-3 py-1 rounded-ctl text-xs font-semibold uppercase tracking-wide text-mute hover:text-ink focus-visible:outline-2 focus-visible:outline-brand"
                >
                  {t(`nav.groups.${group}`)}
                  <NavIcon
                    name="chevron"
                    className={`size-4 shrink-0 transition-transform ${expanded ? "rotate-90" : "rtl:-scale-x-100"}`}
                  />
                </button>
                <div
                  id={listId}
                  hidden={!expanded}
                  className="mt-0.5 flex-col gap-0.5 [&:not([hidden])]:flex"
                >
                  {items.map((item) => (
                    <Item key={item.to} item={item} />
                  ))}
                </div>
              </div>
            );
          })}
        </div>
        <div className="p-3 border-t border-line">
          <NavLink to="/account" className={linkClass}>
            <NavIcon name="account" />
            <span className="truncate">{t("nav.account")}</span>
          </NavLink>
        </div>
      </nav>
    </aside>
  );
}
