# Gupy Bot Protection - Session Expiry & CDP Limitations

## Executive Summary

The Gupy portal implements aggressive bot detection that interferes with automated CDP (Chrome DevTools Protocol) interactions. Key observations:

### Session Expiry Pattern
- Sessions expire after ~5-10 minutes of inactivity
- Page loads trigger re-authentication redirects to `login.gupy.io/candidates/auth`
- CDP click events on certain buttons (e.g., "Desistir", "Ver andamento") may not register

### Detection Mechanisms
1. **Behavior analysis**: Rapid successive clicks from CDP may appear automated
2. **Interaction validation**: Some buttons require specific interaction patterns (hover, focus)
3. **Rate limiting**: Multiple requests in quick succession may trigger blocks

## Workarounds & Strategies

### Session Persistence
- Always verify session before starting work: `curl -s https://login.gupy.io/candidates/applications | grep -c "Minhas candidaturas"`
- Use the browser snapshot to confirm session is active (look for "Minhas candidaturas" heading)
- If redirected to login, manual re-authentication is required

### For Manual Sessions
When working manually in the CDP-attached browser:

1. **Check session first**:
   ```bash
   # Verify you're logged in before any action
   https://login.gupy.io/candidates/applications
   # Should show "Minhas candidaturas" heading
   ```

2. **Avoid rapid actions**:
   - Wait 1-2 seconds between clicks
   - Refresh the page after major interactions
   - Verify the action succeeded before proceeding

3. **Button interaction fallback**:
   ```javascript
   // If click doesn't work, try:
   element.scrollIntoView();
   element.focus();
   element.click();
   ```

### API-First Approach (Recommended)
Instead of browser automation for status checking:

1. Use the Job Hunter API for job data
2. For "desistir" (withdrawing) actions:
   - These require human confirmation on the UI
   - No Gupy API endpoint exists for programmatic withdrawal
   - Document which jobs were withdrawn for idempotency

## Common Scenarios

### Scenario: "Desistir" button does nothing
**Cause**: Gupy's bot protection blocking the click
**Solution**: 
- Refresh the page and try again
- Wait 2-3 seconds between attempts
- If persistent, document for manual handling

### Scenario: "Ver andamento" opens empty modal
**Cause**: SPA routing failure or bot detection
**Solution**:
- Navigate directly to the job URL format:
  `https://jobs.gupy.io/jobs/<job-id>?jobBoardSource=gupy_public_page`
- Check network tab for failed requests

### Scenario: Session expires mid-session
**Cause**: Inactivity timeout or token revocation
**Solution**:
1. Navigate to `https://login.gupy.io/candidates/signin`
2. Re-authenticate
3. Confirm with "logado" when ready

## Pitfall: Assuming CDP clicks always work
The `browser_click` tool may report success but the action never registers. Always verify:
- Check for modals or alerts appearing
- Look for changes in the DOM
- Verify network requests were made
- Take screenshot after action for audit

## References
- Issue #53: Session expiry handling
- `verify_session()` in navigation.py
- Gupy login: `login.gupy.io/candidates/signin`
- Applications page: `login.gupy.io/candidates/applications`