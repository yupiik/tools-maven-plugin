<div id="llm-chat-root" style="display:none"
     data-model-id="{{llmModelId}}"
     data-base="{{siteBase}}"></div>
<script>
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('{{siteBase}}/llm-chat-sw.js', {scope: '/'});
}
</script>
<script src="{{siteBase}}/js/llm-chat.js?v={{projectVersion}}"></script>