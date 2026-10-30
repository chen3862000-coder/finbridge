# Demo Screenshots

- `borrower-demo.png`: anonymous borrower welcome state, 1440x1200.
- `borrower-applications-status.png`: signed-in synthetic borrower application list and visible persisted statuses, 1440x1200.
- `tenant-loans.png`: signed-in tenant loan book with active synthetic loans, 1440x1200.
- `workflow-explorer-selected-application.png`: persisted workflow and step history for a selected application, 1600x1200. Captured with the borrower identity because the tenant Console workflow endpoint currently denies that identity; the screenshot demonstrates the rendered workflow view, not admin authorization.
- `workflow-explorer-mobile.png`: the same workflow view at a 390x844 viewport.
- `payment-owner-status.png`: loan servicing detail showing a succeeded payment and its pinned AlphaPay v1 provider owner, 1440x1200.
- `admin-plugin-registry.png`: signed-in admin view of persisted artifact identities and desired lifecycle state, 1600x1200.
- `admin-plugin-registry-alphapay-audit.png`: AlphaPay v1 lifecycle audit including rollback events, 1600x1206.
- `admin-alphapay-v2-audit.png`: AlphaPay v2 retirement/unload audit after rollback, 1600x1252.
- `admin-tenant-routing-current.png`: signed-in admin view of current Alpha tenant capability bindings and revisions, 1600x1200.
- `admin-tenant-routing-mobile.png`: signed-in admin Tenant Routing at a 390x844 viewport. The routes table continues vertically and the navigation strip scrolls horizontally; browser checks found no page-level horizontal overflow.
- `admin-plugin-registry-mobile.png`: signed-in admin Plugin Registry at a 390x844 viewport; the selected view remains readable, with vertically scrollable content and horizontally scrollable navigation.

All captures show synthetic records only. Browser automation bypassed certificate verification for the generated localhost certificate. Earlier desktop captures bypassed the proxy CSP only in the isolated browser because Vite development HMR injected a refresh preamble; the Compose Console now serves the production preview under CSP. Neither browser bypass changes app runtime configuration. Screenshots are portfolio illustrations, not accessibility certification or production UI/security evidence. No screenshot shows passwords, cookies, tokens, or real personal data.
