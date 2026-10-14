# site/

Static pages for MapMyRoad, built with Jekyll (GitHub Pages compatible). Currently holds the
privacy policy draft (task M0-7, PRD section 8.2: a public privacy policy URL must be live before
the first closed test).

- `index.md`: minimal landing page
- `privacy-policy.md`: privacy policy, published at `/privacy/`. **DRAFT**, needs legal review
  before publication (PRD section 8.2, SECURITY.md section 14). Keep it consistent with PRD
  section 8.1 and the Play Data safety form at every release.
- `_config.yml`: site title and theme

## Publishing (human decision)

- GitHub Pages on a **private** repository requires a paid GitHub plan (Pro, Team or Enterprise).
  On a free plan, Pages works only for public repositories.
- Free alternative: a separate small **public** repository that contains only the contents of
  `site/`, with Pages enabled on it. Copy changes over when the policy changes.
- Either way, Pages can serve from a `/docs` folder or the root of a branch, not from `site/`
  directly, so a GitHub Actions Pages workflow (or the separate repo) is needed.

Which option to use, and when to publish, is the human's decision. Do not publish the draft
without legal review.

## Local preview (optional)

Requires Ruby with the `jekyll` and `jekyll-theme-minimal` gems: `jekyll serve --source site`. No Gemfile is committed yet.
