<footer class="site-footer">
        <div class="footer-inner">
            <div class="footer-social">
                <ul class="social-list">
                {{>socialLinksFooter}}
                </ul>
            </div>
            {{>copyrightLine}}
        </div>
    </footer>

    {{#if searchModal}}{{>searchModal}}{{/if}}

    <script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.0/jquery.min.js"></script>
    {{#if highlightJs}}{{>highlightJs}}{{/if}}
    <script src="{{base}}/js/minisite.js?v={{projectVersion}}"></script>
    <script>$('.page-content-body').generatedNavMenu();</script>
    {{#if llmChat}}{{>llmChatScripts}}{{/if}}
    {{{customScripts}}}
<script>
    (function () {
        // theme toggle
        var root = document.documentElement;
        var toggle = document.getElementById('theme-toggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
                root.setAttribute('data-theme', next);
                try { localStorage.setItem('yupiik-theme', next); } catch (e) {}
            });
        }
        // search modal
        var modal = document.getElementById('searchModal');
        function openModal() { if (modal) modal.classList.add('open'); }
        function closeModal() { if (modal) modal.classList.remove('open'); }
        var sb = document.getElementById('search-button');
        if (sb) sb.addEventListener('click', function (e) { e.preventDefault(); openModal(); });
        if (modal) {
            modal.addEventListener('click', function (e) { if (e.target.closest('[data-close-modal]')) closeModal(); });
            document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeModal(); });
        }
    })();
</script>
</body>
</html>