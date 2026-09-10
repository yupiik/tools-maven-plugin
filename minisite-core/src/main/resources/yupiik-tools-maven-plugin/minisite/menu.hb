<header class="site-header">
        <div class="header-inner">
            <div class="brand">
                <a class="brand-link" title="Home" href="{{base}}/index.html">
                    <img class="brand-logo" src="{{logo}}" alt="logo">
                    <span class="brand-text">{{logoText}}<span class="brand-side">{{logoSideText}}</span></span>
                </a>
            </div>
            <div class="header-actions">
                {{{customMenu}}}
                {{#if search}}{{>search}}{{/if}}
                {{#if blogLink}}{{>blogLink}}{{/if}}
				<ul class="social-list">
				{{>socialLinks}}
				</ul>
				<button class="icon-btn" id="theme-toggle" type="button" title="Toggle theme" aria-label="Toggle theme">
					<svg class="icon-sun" viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2a1 1 0 1 1 0 2 1 1 0 0 1 0-2zm0-16a1 1 0 1 1 0 2 1 1 0 0 1 0-2zM6.34 4.93a1 1 0 1 1 1.41 1.41 1 1 0 0 1-1.41-1.41zm11.32 12.73a1 1 0 1 1 1.41 1.41 1 1 0 0 1-1.41-1.41zM4 11a1 1 0 1 1 0 2 1 1 0 0 1 0-2zm16 0a1 1 0 1 1 0 2 1 1 0 0 1 0-2zM4.93 17.66a1 1 0 1 1 1.41-1.41 1 1 0 0 1-1.41 1.41zm12.73-11.32a1 1 0 1 1 1.41-1.41 1 1 0 0 1-1.41 1.41z"/></svg>
					<svg class="icon-moon" viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.39 5.39 0 0 1-4.4 2.26 5.4 5.4 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z"/></svg>
				</button>
            </div>
        </div>
    </header>
	<div class="page">
{{#if addLeftMenu}}
<minisite-menu-placeholder/>
{{/if}}