<script src="https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.11.2/highlight.min.js" integrity="sha384-pZ4gLQJIEk/VKnGk59xUSQK8Ne/erZQR9cpnPXPLB9iN1tXsVkRVfHYGdFYrSkv1" crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.11.2/languages/bash.min.js" integrity="sha384-efs9Q+5nnPiBO0WMrHtZ2b4ZbGnY0xWwGyXD6wEdpsx5FloMqm1cIVIKG1Bdvg40" crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.11.2/languages/json.min.js" integrity="sha384-NnOc9XV+udt/++RdhhzykFpOtc2D05945J+tYJKxgDKzBhgT/v7WDgIYAfP5LaSa" crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.11.2/languages/dockerfile.min.js" integrity="sha384-0zNCDFuDropscQQX105Gh/3QcoGRmz/FNUFCljVEETiDYRYMhSEyRydgyjQTlgds" crossorigin="anonymous"></script>
    <script>if (!(window.minisite || {}).skipHighlightJs) {
        document.querySelectorAll('.page-content-body pre code').forEach(function (codeBlock) {
            hljs.highlightElement(codeBlock);
        });
        {{#if addCodeCopyButton}}function addCopyButtons(clipboard) {
          document.querySelectorAll('.page-content-body pre').forEach(function (codeBlock) {
            var button = document.createElement('button');
            button.className = 'copy-code-button';
            button.type = 'button';
            button.setAttribute('aria-label', 'Copy code to clipboard');
            button.innerHTML = '<svg class="copy-glyph" viewBox="0 0 16 16" aria-hidden="true"><rect x="5.5" y="5.5" width="8" height="8" rx="1.5"></rect><path d="M10.5 5.5v-1A2 2 0 0 0 8.5 2.5h-5A2 2 0 0 0 1.5 4.5v5a2 2 0 0 0 2 2h1"></path></svg>' +
                '<svg class="copy-check" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8.5l3 3 6-6.5"></path></svg>';
            button.addEventListener('click', function () {
              clipboard.writeText(codeBlock.querySelector('code') ? codeBlock.querySelector('code').innerText : codeBlock.innerText).then(function () {
                button.classList.add('is-copied');
                button.blur();
                setTimeout(function () { button.classList.remove('is-copied'); }, 2000);
              }, function () {
                button.classList.add('is-copied');
                button.querySelector('.copy-check').style.stroke = 'var(--brand-purple)';
              });
            });
            codeBlock.appendChild(button);
          });
        }
        if (navigator && navigator.clipboard) {
         addCopyButtons(navigator.clipboard);
        } else {
         var script = document.createElement('script');
         script.src = '//cdnjs.cloudflare.com/ajax/libs/clipboard-polyfill/2.7.0/clipboard-polyfill.promise.js';
         script.integrity = 'sha256-waClS2re9NUbXRsryKoof+F9qc1gjjIhc2eT7ZbIv94=';
         script.crossOrigin = 'anonymous';
         script.onload = function() {addCopyButtons(clipboard);};
         document.body.appendChild(script);
        }{{/if}}
    }</script>