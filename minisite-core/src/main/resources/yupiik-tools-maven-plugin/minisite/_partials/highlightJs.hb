<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/highlight.min.js" integrity="sha512-d00ajEME7cZhepRqSIVsQVGDJBdZlfHyQLNC6tZXYKTG7iwcF8nhlFuppanz8hYgXr8VvlfKh4gLC25ud3c90A==" crossorigin="anonymous"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/bash.min.js" integrity="sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==" crossorigin="anonymous"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/bash.min.js" integrity="sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==" crossorigin="anonymous"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/json.min.js" integrity="sha512-37sW1XqaJmseHAGNg4N4Y01u6g2do6LZL8tsziiL5CMXGy04Th65OXROw2jeDeXLo5+4Fsx7pmhEJJw77htBFg==" crossorigin="anonymous"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/dockerfile.min.js" integrity="sha512-eRNl3ty7GOJPBN53nxLgtSSj2rkYj5/W0Vg0MFQBw8xAoILeT6byOogENHHCRRvHil4pKQ/HbgeJ5DOwQK3SJA==" crossorigin="anonymous"></script>
    <script>if (!(window.minisite || {}).skipHighlightJs) {
        hljs.highlightAll();
        {{#if addCodeCopyButton}}function addCopyButtons(clipboard) {
          document.querySelectorAll('pre > code').forEach(function (codeBlock) {
          var button = document.createElement('button');
          button.className = 'copy-code-button';
          button.type = 'button';
          button.innerText = 'Copy';
          button.addEventListener('click', function () {
           clipboard.writeText(codeBlock.innerText).then(function () {
           button.blur();
           button.innerText = 'Copied!';
           setTimeout(function () { button.innerText = 'Copy'; }, 2000); }, function (error) { button.innerText = 'Error'; });
          });
          var pre = codeBlock.parentNode;
          if (pre.parentNode.classList.contains('highlight')) {
          var highlight = pre.parentNode; highlight.parentNode.insertBefore(button, highlight);
          } else { pre.parentNode.insertBefore(button, pre); }
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