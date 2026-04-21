(function() {
    var currentTicketId = null;
    var allTickets = [];
    var filteredTickets = [];
    var currentPage = 1;
    var pageSize = 20;
    var currentDetailTicket = null;
    var currentDetailComments = [];
    var assignedSearchTimer = null;
    var mentionSearchTimer = null;
	var currentTicketAiAnswer = "";

    function getTicketRestBase() {
        if (typeof PluginHelper === "undefined" || !PluginHelper.getPluginRestUrl) {
            throw new Error("PluginHelper.getPluginRestUrl is not available");
        }
        return PluginHelper.getPluginRestUrl("tickets");
    }

    function getCommentRestBase() {
        if (typeof PluginHelper === "undefined" || !PluginHelper.getPluginRestUrl) {
            throw new Error("PluginHelper.getPluginRestUrl is not available");
        }
        return PluginHelper.getPluginRestUrl("ticketComments");
    }

    function getIdentitySearchRestBase() {
        if (typeof PluginHelper === "undefined" || !PluginHelper.getPluginRestUrl) {
            throw new Error("PluginHelper.getPluginRestUrl is not available");
        }
        return PluginHelper.getPluginRestUrl("identitySearch");
    }

    function getHeaders() {
        var headers = {
            "Accept": "application/json",
            "Content-Type": "application/json"
        };

        if (typeof PluginHelper !== "undefined" && PluginHelper.getCsrfToken) {
            headers["X-XSRF-TOKEN"] = PluginHelper.getCsrfToken();
        }

        return headers;
    }

    function getPostHeaders() {
        var headers = {
            "Accept": "application/json"
        };

        if (typeof PluginHelper !== "undefined" && PluginHelper.getCsrfToken) {
            headers["X-XSRF-TOKEN"] = PluginHelper.getCsrfToken();
        }

        return headers;
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) {
            return "";
        }

        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
    }

    function truncateText(str, max) {
        if (str === null || str === undefined) {
            return "";
        }

        var text = String(str);
        if (text.length <= max) {
            return text;
        }

        return text.substring(0, max) + "...more";
    }

    function formatCommentDate(raw) {
        if (!raw) return "";
        var d = new Date(typeof raw === "number" ? raw : parseInt(raw, 10));
        if (isNaN(d.getTime())) return String(raw);
        var days   = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
        var months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
        var day    = days[d.getDay()];
        var mon    = months[d.getMonth()];
        var dt     = d.getDate();
        var yr     = d.getFullYear();
        var hr     = d.getHours();
        var min    = String(d.getMinutes()).padStart(2, "0");
        var ampm   = hr >= 12 ? "PM" : "AM";
        hr = hr % 12 || 12;
        var tz = "";
        try {
            tz = " " + Intl.DateTimeFormat().resolvedOptions().timeZone
                    .split("/").pop().replace(/_/g, " ");
        } catch (e) {}
        return day + " " + mon + " " + dt + ", " + yr + " " + hr + ":" + min + ampm + tz;
    }

    function splitErrorAndAccess(errorMessage) {
        var result = {
            affectedAccess: "",
            displayError: errorMessage || ""
        };

        if (!errorMessage || typeof errorMessage !== "string") {
            return result;
        }

        var text = errorMessage.trim();
        var prefix = "Access:";
        var separator = " | Error:";

        if (text.indexOf(prefix) === 0 && text.indexOf(separator) > -1) {
            var sepIndex = text.indexOf(separator);
            var rawAccess = text.substring(prefix.length, sepIndex).trim();
            result.affectedAccess = rawAccess.replace(/\\n/g, "\n");
            result.displayError = text.substring(sepIndex + separator.length).trim();
        }

        return result;
    }

    function badge(status) {
        var css = "tc-open";
        if (status === "ASSIGNED") css = "tc-assigned";
        if (status === "RESOLVED") css = "tc-resolved";
        return '<span class="tc-badge ' + css + '">' + escapeHtml(status || "") + '</span>';
    }

    function severityBadge(severity) {
        var normalized = (severity || "").toLowerCase();
        var css = "tc-sev-medium";
        if (normalized === "high") css = "tc-sev-high";
        if (normalized === "low") css = "tc-sev-low";
        return '<span class="tc-badge ' + css + '">' + escapeHtml(severity || "") + '</span>';
    }

    function handleJson(resp) {
        if (!resp.ok) {
            return resp.text().then(function(t) {
                throw new Error(t || ("HTTP " + resp.status));
            });
        }
        return resp.json();
    }

    function parseAiSummary(aiSummary) {
        if (!aiSummary || typeof aiSummary !== "string") {
            return null;
        }

        var trimmed = aiSummary.trim();
        if (!trimmed || trimmed.charAt(0) !== "{") {
            return null;
        }

        try {
            return JSON.parse(trimmed);
        } catch (e) {
            return null;
        }
    }

    function renderAiSummary(aiSummary) {
        var parsed = parseAiSummary(aiSummary);

        if (!parsed) {
            return '<div class="tc-detail-value">' + escapeHtml(aiSummary || "No AI summary yet") + '</div>';
        }

        var confidenceDisplay = "";
        if (parsed.confidence !== null && parsed.confidence !== undefined && parsed.confidence !== "") {
            confidenceDisplay = escapeHtml(parsed.confidence);
        }

        var html = [];
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Root Cause</div><div class="tc-detail-value">' + escapeHtml(parsed.rootCause || "") + '</div></div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Recommendation</div><div class="tc-detail-value">' + escapeHtml(parsed.recommendation || "") + '</div></div>');
        html.push('<div class="tc-inline-row">');
        html.push('<div class="tc-detail-block" style="flex:1 1 180px;"><div class="tc-detail-label">Severity</div><div class="tc-detail-value">' + severityBadge(parsed.severity || "") + '</div></div>');
        html.push('<div class="tc-detail-block" style="flex:1 1 180px;"><div class="tc-detail-label">Confidence</div><div class="tc-detail-value">' + confidenceDisplay + '</div></div>');
        html.push('</div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Suggested Assignment Group</div><div class="tc-detail-value">' + escapeHtml(parsed.assignmentGroup || "") + '</div></div>');
        return html.join('');
    }

    function getPagedRows(rows) {
        var start = (currentPage - 1) * pageSize;
        var end = start + pageSize;
        return rows.slice(start, end);
    }

    function normalizeValue(value) {
        if (value === null || value === undefined) {
            return "";
        }
        return String(value).toLowerCase();
    }

    function applyClientFilters(rows) {
        var searchEl = document.getElementById("tc-search-filter");
        var searchText = searchEl ? normalizeValue(searchEl.value).trim() : "";

        if (!searchText) {
            return rows.slice(0);
        }

        return rows.filter(function(row) {
            return normalizeValue(row.id).indexOf(searchText) > -1 ||
                   normalizeValue(row.sourceType).indexOf(searchText) > -1 ||
                   normalizeValue(row.applicationName).indexOf(searchText) > -1 ||
                   normalizeValue(row.identityName).indexOf(searchText) > -1 ||
                   normalizeValue(row.status).indexOf(searchText) > -1 ||
                   normalizeValue(row.assignedTo).indexOf(searchText) > -1;
        });
    }

    function refreshGridFromCurrentFilters() {
        filteredTickets = applyClientFilters(allTickets);
        currentPage = 1;
        renderTable(filteredTickets);
    }

    function renderSummaryBar(rows) {
        var el = document.getElementById("tc-summary-bar");
        if (!el) {
            return;
        }

        var total = rows.length;
        var openCount = 0;
        var assignedCount = 0;
        var resolvedCount = 0;

        rows.forEach(function(row) {
            if (row.status === "OPEN") openCount++;
            if (row.status === "ASSIGNED") assignedCount++;
            if (row.status === "RESOLVED") resolvedCount++;
        });

        var html = [];
        html.push('<div class="tc-summary-box"><div class="tc-summary-label">Total Tickets</div><div class="tc-summary-value">' + total + '</div></div>');
        html.push('<div class="tc-summary-box"><div class="tc-summary-label">Open</div><div class="tc-summary-value">' + openCount + '</div></div>');
        html.push('<div class="tc-summary-box"><div class="tc-summary-label">Assigned</div><div class="tc-summary-value">' + assignedCount + '</div></div>');
        html.push('<div class="tc-summary-box"><div class="tc-summary-label">Resolved</div><div class="tc-summary-value">' + resolvedCount + '</div></div>');
        el.innerHTML = html.join('');
    }

    function renderPagination(totalRows) {
        var totalPages = Math.max(1, Math.ceil(totalRows / pageSize));
        var start = totalRows === 0 ? 0 : ((currentPage - 1) * pageSize) + 1;
        var end = Math.min(currentPage * pageSize, totalRows);

        var html = [];
        html.push('<div class="tc-pagination">');
        html.push('<div><button class="tc-btn secondary small" id="tc-prev-btn"' + (currentPage <= 1 ? ' disabled="disabled"' : '') + '>Prev</button></div>');
        html.push('<div class="tc-pagination-center">Page ' + currentPage + ' of ' + totalPages + ' • Showing ' + start + '-' + end + ' of ' + totalRows + '</div>');
        html.push('<div><button class="tc-btn secondary small" id="tc-next-btn"' + (currentPage >= totalPages ? ' disabled="disabled"' : '') + '>Next</button></div>');
        html.push('</div>');
        return html.join('');
    }

    function renderTable(rows) {
        var container = document.getElementById("tc-ticket-table");

        if (!rows || rows.length === 0) {
            container.innerHTML = '<div class="tc-empty">No tickets found.</div>';
            renderSummaryBar([]);
            return;
        }

        renderSummaryBar(rows);

        var pagedRows = getPagedRows(rows);

        var html = [];
        html.push('<div class="tc-table-wrap">');
        html.push('<table class="tc-table">');
        html.push('<thead><tr><th>ID</th><th>Source</th><th>Application</th><th>Identity</th><th>Status</th><th>Assigned To</th><th>Error</th></tr></thead><tbody>');

        pagedRows.forEach(function(row) {
            var parsedError = splitErrorAndAccess(row.errorMessage);

            html.push('<tr>');
            html.push('<td><span class="tc-row-link" data-ticket-id="' + escapeHtml(row.id) + '">' + escapeHtml(row.id) + '</span></td>');
            html.push('<td>' + escapeHtml(row.sourceType) + '</td>');
            html.push('<td>' + escapeHtml(row.applicationName) + '</td>');
            html.push('<td>' + escapeHtml(row.identityName) + '</td>');
            html.push('<td>' + badge(row.status) + '</td>');
            html.push('<td>' + escapeHtml(row.assignedTo || "") + '</td>');
            html.push('<td title="' + escapeHtml(parsedError.displayError || "") + '">' + escapeHtml(truncateText(parsedError.displayError, 60)) + '</td>');
            html.push('</tr>');
        });

        html.push('</tbody></table>');
        html.push('</div>');
        html.push(renderPagination(rows.length));

        container.innerHTML = html.join('');

        var links = container.querySelectorAll(".tc-row-link");
        for (var i = 0; i < links.length; i++) {
            links[i].addEventListener("click", function() {
                var id = this.getAttribute("data-ticket-id");
                if (id) {
                    window.TicketUI.loadTicket(id);
                }
            });
        }

        var prevBtn = document.getElementById("tc-prev-btn");
        if (prevBtn) {
            prevBtn.addEventListener("click", function() {
                if (currentPage > 1) {
                    currentPage--;
                    renderTable(filteredTickets);
                }
            });
        }

        var nextBtn = document.getElementById("tc-next-btn");
        if (nextBtn) {
            nextBtn.addEventListener("click", function() {
                var totalPages = Math.max(1, Math.ceil(filteredTickets.length / pageSize));
                if (currentPage < totalPages) {
                    currentPage++;
                    renderTable(filteredTickets);
                }
            });
        }
    }

    function renderComments(comments) {
        if (!comments || comments.length === 0) {
            return '<div class="tc-empty">No comments yet.</div>';
        }

        var html = [];
        comments.forEach(function(comment) {
            var name = comment.commentBy || "System";
            var parts = name.trim().split(/\s+/);
            var initials = parts.length >= 2
                ? (parts[0][0] + parts[1][0]).toUpperCase()
                : name.substring(0, 2).toUpperCase();

            html.push('<div class="tc-comment-item">');
            html.push('<div class="tc-comment-avatar">' + escapeHtml(initials) + '</div>');
            html.push('<div class="tc-comment-bubble">');
            html.push('<div class="tc-comment-meta"><span class="tc-comment-author">' + escapeHtml(name) + '</span> &bull; ' + escapeHtml(formatCommentDate(comment.created)) + '</div>');
            html.push('<div class="tc-comment-body">' + escapeHtml(comment.commentText || "") + '</div>');
            html.push('</div></div>');
        });
        return html.join('');
    }

    function renderSuggestions(items, onSelectJs) {
        if (!items || items.length === 0) {
            return '<div class="tc-suggest-box"><div class="tc-suggest-item"><div class="tc-suggest-title">No users found</div></div></div>';
        }

        var html = [];
        html.push('<div class="tc-suggest-box">');
        items.forEach(function(item, idx) {
            var dName = item.displayName || item.username || "";
            var parts = dName.trim().split(/\s+/);
            var initials = parts.length >= 2
                ? (parts[0][0] + parts[1][0]).toUpperCase()
                : dName.substring(0, 2).toUpperCase();

            html.push('<div class="tc-suggest-item" data-idx="' + idx + '">');
            html.push('<div class="tc-suggest-avatar">' + escapeHtml(initials) + '</div>');
            html.push('<div class="tc-suggest-info">');
            html.push('<div class="tc-suggest-title">' + escapeHtml(item.username || "") + ' — ' + escapeHtml(item.displayName || "") + '</div>');
            html.push('<div class="tc-suggest-meta">' + escapeHtml(item.email || "") + '</div>');
            html.push('</div></div>');
        });
        html.push('</div>');
        return html.join('');
    }

    function searchIdentities(term, callback) {
        if (!term || term.trim().length < 1) {
            callback([]);
            return;
        }

        var url = getIdentitySearchRestBase()
            + "/search?q=" + encodeURIComponent(term)
            + "&max=10";

        fetch(url, {
            method: "GET",
            credentials: "same-origin",
            headers: getHeaders()
        })
        .then(handleJson)
        .then(function(rows) {
            callback(rows || []);
        })
        .catch(function() {
            callback([]);
        });
    }

    function wireAssignedLookup() {
        var input = document.getElementById("tc-assigned-input");
        var holder = document.getElementById("tc-assigned-suggest-holder");
        if (!input || !holder) {
            return;
        }

        input.addEventListener("input", function() {
            var term = input.value || "";
            clearTimeout(assignedSearchTimer);

            assignedSearchTimer = setTimeout(function() {
                searchIdentities(term, function(items) {
                    if (!term) {
                        holder.innerHTML = "";
                        return;
                    }

                    holder.innerHTML = renderSuggestions(items);

                    var suggestionNodes = holder.querySelectorAll(".tc-suggest-item[data-idx]");
                    for (var i = 0; i < suggestionNodes.length; i++) {
                        suggestionNodes[i].addEventListener("click", function() {
                            var idx = parseInt(this.getAttribute("data-idx"), 10);
                            if (!isNaN(idx) && items[idx]) {
                                input.value = items[idx].username || "";
                                holder.innerHTML = "";
                            }
                        });
                    }
                });
            }, 250);
        });

        input.addEventListener("blur", function() {
            setTimeout(function() {
                holder.innerHTML = "";
            }, 200);
        });
    }

    function getMentionToken(text, caretPos) {
        var left = text.substring(0, caretPos);
        var atPos = left.lastIndexOf("@");
        if (atPos < 0) {
            return null;
        }

        var beforeAt = atPos > 0 ? left.charAt(atPos - 1) : " ";
        if (beforeAt !== " " && beforeAt !== "\n" && beforeAt !== "\t") {
            return null;
        }

        var token = left.substring(atPos + 1);
        if (token.indexOf(" ") >= 0 || token.indexOf("\n") >= 0 || token.indexOf("\t") >= 0) {
            return null;
        }

        return {
            start: atPos,
            query: token
        };
    }

    function replaceMention(textarea, mentionToken, selectedUsername) {
        var text = textarea.value || "";
        var caretPos = textarea.selectionStart || 0;

        var before = text.substring(0, mentionToken.start);
        var after = text.substring(caretPos);

        var newText = before + "@" + selectedUsername + " " + after;
        textarea.value = newText;

        var newPos = (before + "@" + selectedUsername + " ").length;
        textarea.focus();
        textarea.setSelectionRange(newPos, newPos);
    }

    function wireMentionLookup() {
        var textarea = document.getElementById("tc-comment-text");
        var holder = document.getElementById("tc-mention-suggest-holder");
        if (!textarea || !holder) {
            return;
        }

        var latestToken = null;

        textarea.addEventListener("input", function() {
            var caretPos = textarea.selectionStart || 0;
            var token = getMentionToken(textarea.value || "", caretPos);
            latestToken = token;

            clearTimeout(mentionSearchTimer);

            if (!token || token.query.length < 1) {
                holder.innerHTML = "";
                return;
            }

            mentionSearchTimer = setTimeout(function() {
                searchIdentities(token.query, function(items) {
                    if (!latestToken) {
                        holder.innerHTML = "";
                        return;
                    }

                    holder.innerHTML = renderSuggestions(items);

                    var suggestionNodes = holder.querySelectorAll(".tc-suggest-item[data-idx]");
                    for (var i = 0; i < suggestionNodes.length; i++) {
                        suggestionNodes[i].addEventListener("click", function() {
                            var idx = parseInt(this.getAttribute("data-idx"), 10);
                            if (!isNaN(idx) && items[idx]) {
                                replaceMention(textarea, latestToken, items[idx].username || "");
                                holder.innerHTML = "";
                            }
                        });
                    }
                });
            }, 250);
        });

        textarea.addEventListener("blur", function() {
            setTimeout(function() {
                holder.innerHTML = "";
            }, 200);
        });
    }

    function renderAssignmentEditor(ticket) {
        return '' +
            '<div class="tc-detail-block">' +
                '<div class="tc-detail-label">Assigned To</div>' +
                '<div id="tc-assigned-container">' +
                    '<div class="tc-assignment-edit">' +
                        '<div class="tc-detail-value" style="flex:1 1 250px;">' + escapeHtml(ticket.assignedTo || "") + '</div>' +
                        '<button class="tc-btn secondary small" id="tc-edit-assigned-btn">Edit</button>' +
                    '</div>' +
                '</div>' +
            '</div>';
    }

    function renderAssignmentEditMode(ticket) {
        var container = document.getElementById("tc-assigned-container");
        if (!container) {
            return;
        }

        container.innerHTML =
            '<div class="tc-relative">' +
                '<div class="tc-assignment-edit">' +
                    '<input id="tc-assigned-input" class="tc-input" type="text" value="' + escapeHtml(ticket.assignedTo || "") + '" style="flex:1 1 250px;" />' +
                    '<button class="tc-btn small" id="tc-save-assigned-btn">Save</button>' +
                    '<button class="tc-btn secondary small" id="tc-cancel-assigned-btn">Cancel</button>' +
                '</div>' +
                '<div id="tc-assigned-suggest-holder"></div>' +
                '<div id="tc-assign-msg" class="tc-muted"></div>' +
            '</div>';

        var saveBtn = document.getElementById("tc-save-assigned-btn");
        var cancelBtn = document.getElementById("tc-cancel-assigned-btn");

        if (saveBtn) {
            saveBtn.addEventListener("click", function() {
                window.TicketUI.saveAssignedTo();
            });
        }

        if (cancelBtn) {
            cancelBtn.addEventListener("click", function() {
                renderDetail(currentDetailTicket, currentDetailComments);
            });
        }

        wireAssignedLookup();
    }

    function renderDetail(ticket, comments) {
        var el = document.getElementById("tc-ticket-detail");
        if (!ticket) {
            el.innerHTML = '<div class="tc-empty">Ticket not found.</div>';
            return;
        }

        currentTicketId = ticket.id;
        currentDetailTicket = ticket;
        currentDetailComments = comments || [];

        el.scrollTop = 0;

        var idLabel = document.getElementById("tc-detail-ticket-id");
        if (idLabel) { idLabel.textContent = "#" + ticket.id; }

        var parsedError = splitErrorAndAccess(ticket.errorMessage);

        var html = [];

        html.push('<div class="tc-detail-section">');
        html.push('<div class="tc-section-title">Ticket Information</div>');
        html.push('<div class="tc-detail-grid">');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Ticket ID</div><div class="tc-detail-value">' + escapeHtml(ticket.id) + '</div></div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Status</div><div class="tc-detail-value">' + badge(ticket.status) + '</div></div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Application</div><div class="tc-detail-value">' + escapeHtml(ticket.applicationName) + '</div></div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Identity</div><div class="tc-detail-value">' + escapeHtml(ticket.identityName) + '</div></div>');
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Operation</div><div class="tc-detail-value">' + escapeHtml(ticket.operation) + '</div></div>');

        if (parsedError.affectedAccess) {
            html.push('<div class="tc-detail-block"><div class="tc-detail-label">Affected Access</div><div class="tc-detail-value">' + escapeHtml(parsedError.affectedAccess) + '</div></div>');
        }

        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Error Message</div><div class="tc-detail-value">' + escapeHtml(parsedError.displayError) + '</div></div>');
        html.push(renderAssignmentEditor(ticket));
        html.push('<div class="tc-detail-block"><div class="tc-detail-label">Request ID</div><div class="tc-detail-value">' + escapeHtml(ticket.requestId || "") + '</div></div>');
        html.push('</div>');
        html.push('</div>');

        html.push('<div class="tc-detail-section">');
        html.push('<div class="tc-section-title">AI Analysis</div>');
        html.push(renderAiSummary(ticket.aiSummary));
        html.push('</div>');
		
		html.push('<div class="tc-detail-section">');
		html.push('<div class="tc-section-title">Ask AI about this Ticket</div>');
		html.push('<div class="tc-ticket-ai-box">');

		html.push('<div class="tc-detail-label">Ask a question about this selected ticket only</div>');

		html.push('<div class="tc-ticket-ai-quick">');
		html.push('<button class="tc-chip" onclick="document.getElementById(\'tc-ticket-ai-question\').value=\'Why did this failure happen?\';">Why did this fail?</button>');
		html.push('<button class="tc-chip" onclick="document.getElementById(\'tc-ticket-ai-question\').value=\'What should I do to fix this issue?\';">Fix steps</button>');
		html.push('<button class="tc-chip" onclick="document.getElementById(\'tc-ticket-ai-question\').value=\'What access is impacted?\';">Access impact</button>');
		html.push('<button class="tc-chip" onclick="document.getElementById(\'tc-ticket-ai-question\').value=\'Who should handle this issue?\';">Assignment</button>');
		html.push('</div>');

		html.push('<textarea id="tc-ticket-ai-question" class="tc-textarea tc-ticket-ai-textarea" placeholder="Example: What access is impacted in this ticket?"></textarea>');

		html.push('<div class="tc-ticket-ai-actions">');
		html.push('<button class="tc-btn" id="tc-ticket-ai-ask-btn">Ask AI</button>');
		html.push('</div>');

		html.push('<div id="tc-ticket-ai-msg" class="tc-muted"></div>');
		html.push('<div id="tc-ticket-ai-answer" class="tc-ticket-ai-answer" style="display:none;"></div>');

		html.push('</div>');
		html.push('</div>');

        html.push('<div class="tc-detail-section">');
        html.push('<div class="tc-inline-row" style="justify-content:space-between;">');
        html.push('<div class="tc-section-title" style="margin-bottom:0;">Actions</div>');
        html.push('<button class="tc-btn secondary small" id="tc-resolve-btn">Resolve</button>');
        html.push('</div>');
        html.push('</div>');

        html.push('<div class="tc-comment-box">');
        html.push('<div class="tc-section-title" style="margin-bottom:10px;">Chatter</div>');
        html.push(renderComments(comments));
        html.push('<div class="tc-comment-form">');
        html.push('<div class="tc-comment-row tc-relative">');
        html.push('<textarea id="tc-comment-text" class="tc-textarea" placeholder="Type comment here. Example: @SAP-Team please check this issue"></textarea>');
        html.push('<div id="tc-mention-suggest-holder"></div>');
        html.push('</div>');
        html.push('<button class="tc-btn" id="tc-add-comment-btn">Add Comment</button>');
        html.push('<div id="tc-comment-msg" class="tc-muted"></div>');
        html.push('</div>');
        html.push('</div>');

        el.innerHTML = html.join('');

        var resolveBtn = document.getElementById("tc-resolve-btn");
        if (resolveBtn) {
            resolveBtn.addEventListener("click", function() {
                window.TicketUI.resolveCurrent();
            });
        }

        var addCommentBtn = document.getElementById("tc-add-comment-btn");
        if (addCommentBtn) {
            addCommentBtn.addEventListener("click", function() {
                window.TicketUI.addComment();
            });
        }

        var editAssignedBtn = document.getElementById("tc-edit-assigned-btn");
        if (editAssignedBtn) {
            editAssignedBtn.addEventListener("click", function() {
                renderAssignmentEditMode(ticket);
            });
        }
		
		var askBtn = document.getElementById("tc-ticket-ai-ask-btn");
		if (askBtn) {
		    askBtn.addEventListener("click", function() {
		        window.TicketUI.askAboutCurrentTicket();
		    });
		}

        wireMentionLookup();
    }

    function wireFilterActions() {
        var searchBtn = document.getElementById("tc-search-apply-btn");
        var searchInput = document.getElementById("tc-search-filter");

        if (searchBtn) {
            searchBtn.addEventListener("click", function() {
                window.TicketUI.applySearch();
            });
        }

        if (searchInput) {
            searchInput.addEventListener("keydown", function(e) {
                if (e.key === "Enter") {
                    window.TicketUI.applySearch();
                }
            });
        }
    }

    window.TicketUI = {
        loadTickets: function() {
            var statusEl = document.getElementById("tc-status-filter");
            var status = statusEl ? statusEl.value : "";
            var url = getTicketRestBase() + "/list";

            if (status) {
                url += "?status=" + encodeURIComponent(status);
            }

            fetch(url, {
                method: "GET",
                credentials: "same-origin",
                headers: getHeaders()
            })
            .then(handleJson)
            .then(function(rows) {
                allTickets = rows || [];
                filteredTickets = applyClientFilters(allTickets);
                currentPage = 1;

                var label = document.getElementById("tc-filter-label");
                if (label) { label.textContent = status ? status + " only" : ""; }

                if (allTickets.length === 0) {
                    renderSummaryBar([]);
                    document.getElementById("tc-ticket-table").innerHTML =
                        '<div class="tc-empty" style="padding:20px;text-align:center;">' +
                        '<strong>No tickets available.</strong><br/>' +
                        'You currently do not have access to any tickets. ' +
                        'You will be able to view a ticket once you are @mentioned in its chatter.' +
                        '</div>';
                } else {
                    renderTable(filteredTickets);
                }
            })
            .catch(function(err) {
                document.getElementById("tc-ticket-table").innerHTML =
                    '<div class="tc-empty">Failed to load tickets: ' + escapeHtml(err.message) + '</div>';
            });
        },

        applyFilter: function() {
            this.loadTickets();
        },

        applySearch: function() {
            refreshGridFromCurrentFilters();
        },
		
		askAboutCurrentTicket: function() {
		    var msgEl = document.getElementById("tc-ticket-ai-msg");
		    var answerEl = document.getElementById("tc-ticket-ai-answer");
		    var questionEl = document.getElementById("tc-ticket-ai-question");

		    if (!currentTicketId || !questionEl) return;

		    var question = (questionEl.value || "").trim();
		    if (!question) {
		        msgEl.className = "tc-error";
		        msgEl.innerHTML = "Enter a question first.";
		        return;
		    }

		    msgEl.className = "tc-muted";
		    msgEl.innerHTML = "Checking this ticket...";

		    fetch(getTicketRestBase() + "/ask?id=" + encodeURIComponent(currentTicketId) + "&question=" + encodeURIComponent(question), {
		        method: "POST",
		        credentials: "same-origin",
		        headers: getPostHeaders()
		    })
		    .then(handleJson)
		    .then(function(resp) {
		        var answer = (resp && resp.message) ? resp.message : "Not available in this ticket";

		        answerEl.style.display = "block";
		        answerEl.innerHTML = escapeHtml(answer);

		        msgEl.className = "tc-success";
		        msgEl.innerHTML = "Answer generated.";
		    })
		    .catch(function(err) {
		        answerEl.style.display = "block";
		        answerEl.innerHTML = "Not available in this ticket";

		        msgEl.className = "tc-error";
		        msgEl.innerHTML = "Failed: " + err.message;
		    });
		},

        loadTicket: function(id) {
            Promise.all([
                fetch(getTicketRestBase() + "/get?id=" + encodeURIComponent(id), {
                    method: "GET",
                    credentials: "same-origin",
                    headers: getHeaders()
                }).then(handleJson),
                fetch(getCommentRestBase() + "/list?ticketId=" + encodeURIComponent(id), {
                    method: "GET",
                    credentials: "same-origin",
                    headers: getHeaders()
                }).then(handleJson)
            ])
            .then(function(result) {
                renderDetail(result[0], result[1]);
            })
            .catch(function(err) {
                document.getElementById("tc-ticket-detail").innerHTML =
                    '<div class="tc-empty">Failed to load ticket details: ' + escapeHtml(err.message) + '</div>';
            });
        },

        saveAssignedTo: function() {
            if (!currentDetailTicket || !currentDetailTicket.id) {
                return;
            }

            var input = document.getElementById("tc-assigned-input");
            var msgEl = document.getElementById("tc-assign-msg");
            var assignedTo = input ? input.value : "";

            var url = getTicketRestBase()
                + "/assign?id=" + encodeURIComponent(currentDetailTicket.id)
                + "&assignedTo=" + encodeURIComponent(assignedTo);

            fetch(url, {
                method: "POST",
                credentials: "same-origin",
                headers: getPostHeaders()
            })
            .then(handleJson)
            .then(function(resp) {
                currentDetailTicket.assignedTo = assignedTo;

                for (var i = 0; i < allTickets.length; i++) {
                    if (String(allTickets[i].id) === String(currentDetailTicket.id)) {
                        allTickets[i].assignedTo = assignedTo;
                        allTickets[i].status = "ASSIGNED";
                        currentDetailTicket.status = "ASSIGNED";
                        break;
                    }
                }

                filteredTickets = applyClientFilters(allTickets);

                if (msgEl) {
                    msgEl.className = "tc-success";
                    msgEl.innerHTML = escapeHtml(resp.message || "Assigned To updated");
                }

                renderTable(filteredTickets);
                renderDetail(currentDetailTicket, currentDetailComments);
            })
            .catch(function(err) {
                if (msgEl) {
                    msgEl.className = "tc-error";
                    msgEl.innerHTML = escapeHtml("Failed to update assignment: " + err.message);
                }
            });
        },

        addComment: function() {
            var msgEl = document.getElementById("tc-comment-msg");
            if (!currentTicketId) {
                if (msgEl) {
                    msgEl.className = "tc-error";
                    msgEl.innerHTML = "Select a ticket first.";
                }
                return;
            }

            var commentText = document.getElementById("tc-comment-text").value || "";

            fetch(getTicketRestBase() + "/me", {
                method: "GET",
                credentials: "same-origin",
                headers: getHeaders()
            })
            .then(handleJson)
            .then(function(meResp) {
                var commentBy = (meResp && meResp.message) ? meResp.message : "Unknown";
                var url = getCommentRestBase()
                    + "/add?ticketId=" + encodeURIComponent(currentTicketId)
                    + "&commentBy=" + encodeURIComponent(commentBy)
                    + "&commentText=" + encodeURIComponent(commentText);
                return fetch(url, {
                    method: "POST",
                    credentials: "same-origin",
                    headers: getPostHeaders()
                });
            })
            .then(handleJson)
            .then(function(resp) {
                if (msgEl) {
                    msgEl.className = "tc-success";
                    msgEl.innerHTML = escapeHtml(resp.message || "Comment added");
                }
                window.TicketUI.loadTicket(currentTicketId);
            })
            .catch(function(err) {
                if (msgEl) {
                    msgEl.className = "tc-error";
                    msgEl.innerHTML = escapeHtml("Failed to add comment: " + err.message);
                }
            });
        },

        resolveCurrent: function() {
            var url = getTicketRestBase()
                + "/resolve?id=" + encodeURIComponent(currentTicketId)
                + "&resolutionNotes=" + encodeURIComponent("Resolved from UI");

            fetch(url, {
                method: "POST",
                credentials: "same-origin",
                headers: getPostHeaders()
            })
            .then(handleJson)
            .then(function() {
                if (currentDetailTicket) {
                    currentDetailTicket.status = "RESOLVED";
                }

                for (var i = 0; i < allTickets.length; i++) {
                    if (String(allTickets[i].id) === String(currentTicketId)) {
                        allTickets[i].status = "RESOLVED";
                        break;
                    }
                }

                filteredTickets = applyClientFilters(allTickets);

                window.TicketUI.loadTicket(currentTicketId);
                renderTable(filteredTickets);
            })
            .catch(function(err) {
                var msgEl = document.getElementById("tc-comment-msg");
                if (msgEl) {
                    msgEl.className = "tc-error";
                    msgEl.innerHTML = escapeHtml("Failed to resolve ticket: " + err.message);
                }
            });
        },

        seedSample: function() {
            var url = getTicketRestBase()
                + "/create?sourceType=" + encodeURIComponent("LCM")
                + "&applicationName=" + encodeURIComponent("SAP")
                + "&identityName=" + encodeURIComponent("jdoe")
                + "&operation=" + encodeURIComponent("CREATE")
                + "&errorMessage=" + encodeURIComponent("User not found in SAP")
                + "&status=" + encodeURIComponent("OPEN");

            fetch(url, {
                method: "POST",
                credentials: "same-origin",
                headers: getPostHeaders()
            })
            .then(handleJson)
            .then(function() {
                window.TicketUI.loadTickets();
            })
            .catch(function(err) {
                document.getElementById("tc-ticket-table").innerHTML =
                    '<div class="tc-empty">Failed to create sample ticket: ' + escapeHtml(err.message) + '</div>';
            });
        },

        downloadTickets: function() {
            if (!filteredTickets || filteredTickets.length === 0) {
                alert("No tickets to download.");
                return;
            }

            var statusEl = document.getElementById("tc-status-filter");
            var status = statusEl ? statusEl.value : "";
            var searchEl = document.getElementById("tc-search-filter");
            var searchText = searchEl ? (searchEl.value || "").trim() : "";

            var label = status ? status.toLowerCase() : "all";
            if (searchText) {
                label += "-search";
            }

            var now = new Date();
            var ts = now.getFullYear() + "-"
                + String(now.getMonth() + 1).padStart(2, "0") + "-"
                + String(now.getDate()).padStart(2, "0");

            var csvFilename = "tickets-" + label + "-" + ts + ".csv";
            var zipFilename = "tickets-" + label + "-" + ts + ".zip";

            var csvContent = buildCsvContent(filteredTickets);
            var zipBytes   = buildZip(csvFilename, csvContent);

            var blob = new Blob([zipBytes], { type: "application/zip" });
            var url  = URL.createObjectURL(blob);
            var a    = document.createElement("a");
            a.href     = url;
            a.download = zipFilename;
            document.body.appendChild(a);
            a.click();
            setTimeout(function() { document.body.removeChild(a); URL.revokeObjectURL(url); }, 1000);
        }
    };

    function buildCsvContent(tickets) {
        if (!tickets || tickets.length === 0) { return "No data"; }

        var keySet = {};
        tickets.forEach(function(t) { Object.keys(t).forEach(function(k) { keySet[k] = true; }); });

        var preferred = ["id", "sourceType", "applicationName", "identityName", "operation",
                         "status", "assignedTo", "requestId", "created", "resolved",
                         "resolutionNotes", "errorMessage", "aiSummary"];
        var keys = preferred.filter(function(k) { return keySet[k]; });
        Object.keys(keySet).forEach(function(k) { if (keys.indexOf(k) === -1) keys.push(k); });

        var headers = keys.map(function(k) {
            return k.replace(/([A-Z])/g, " $1").replace(/^./, function(s) { return s.toUpperCase(); });
        });

        var lines = [headers.join(",")];
        tickets.forEach(function(t) {
            var row = keys.map(function(k) {
                var val = (t[k] === null || t[k] === undefined) ? "" : String(t[k]);
                val = val.replace(/[\r\n]/g, " ");
                if (val.indexOf(",") > -1 || val.indexOf('"') > -1) {
                    val = '"' + val.replace(/"/g, '""') + '"';
                }
                return val;
            });
            lines.push(row.join(","));
        });

        return lines.join("\r\n");
    }

    function buildZip(filename, content) {
        function str2bytes(s) {
            var b = new Uint8Array(s.length);
            for (var i = 0; i < s.length; i++) { b[i] = s.charCodeAt(i) & 0xff; }
            return b;
        }

        function crc32(data) {
            var table = [];
            for (var n = 0; n < 256; n++) {
                var c = n;
                for (var k = 0; k < 8; k++) { c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1); }
                table[n] = c;
            }
            var crc = 0xffffffff;
            for (var i = 0; i < data.length; i++) { crc = table[(crc ^ data[i]) & 0xff] ^ (crc >>> 8); }
            return (crc ^ 0xffffffff) >>> 0;
        }

        function u16(n) { return [n & 0xff, (n >> 8) & 0xff]; }
        function u32(n) { return [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >> 24) & 0xff]; }

        var fileData = str2bytes(content);
        var crc      = crc32(fileData);
        var fname    = str2bytes(filename);
        var d        = new Date();
        var dosDate  = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
        var dosTime  = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);

        var lfh = [0x50,0x4b,0x03,0x04, 20,0, 0,0, 0,0]
            .concat(u16(dosTime)).concat(u16(dosDate))
            .concat(u32(crc))
            .concat(u32(fileData.length)).concat(u32(fileData.length))
            .concat(u16(fname.length)).concat([0,0]);

        var localEntry = lfh.concat(Array.from(fname)).concat(Array.from(fileData));

        var cdfh = [0x50,0x4b,0x01,0x02, 20,0, 20,0, 0,0, 0,0]
            .concat(u16(dosTime)).concat(u16(dosDate))
            .concat(u32(crc))
            .concat(u32(fileData.length)).concat(u32(fileData.length))
            .concat(u16(fname.length)).concat([0,0,0,0,0,0,0,0,0,0,0,0])
            .concat(u32(0))
            .concat(Array.from(fname));

        var eocd = [0x50,0x4b,0x05,0x06, 0,0, 0,0, 1,0, 1,0]
            .concat(u32(cdfh.length)).concat(u32(localEntry.length))
            .concat([0,0]);

        var all = new Uint8Array(localEntry.length + cdfh.length + eocd.length);
        all.set(new Uint8Array(localEntry), 0);
        all.set(new Uint8Array(cdfh), localEntry.length);
        all.set(new Uint8Array(eocd), localEntry.length + cdfh.length);
        return all;
    }

    document.addEventListener("DOMContentLoaded", function() {
        var filters = document.querySelector(".tc-filters");
        if (filters && !document.getElementById("tc-search-filter")) {
            var searchHtml = '' +
                '<input id="tc-search-filter" class="tc-input tc-search-input" type="text" ' +
                'placeholder="Search ID, Source, Application, Identity, Status, Assigned To" />' +
                '<button class="tc-btn secondary" id="tc-search-apply-btn">Search</button>';

            filters.insertAdjacentHTML("beforeend", searchHtml);
        }

        wireFilterActions();
        window.TicketUI.loadTickets();
    });
})();