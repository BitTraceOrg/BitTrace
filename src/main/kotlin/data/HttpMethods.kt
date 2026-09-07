package org.bittrace.data

/**
 * Every HTTP method the app offers, anywhere it offers a choice of one.
 *
 * There were three of these: the API client's picker (seven), the grid's method
 * filter (sixteen), and the search band's facet column, which derived its list
 * from whatever had been captured. So the same question — "which methods are
 * there?" — had three different answers depending on which panel you asked, and
 * the band's answer could not offer DELETE until a DELETE had happened, which is
 * exactly when you stop needing to ask for it.
 *
 * RFC 9110's set plus PATCH and the WebDAV verbs that turn up in real traffic.
 * Ordered by how often they are wanted rather than alphabetically: this is a
 * list people pick from, and GET is not near G.
 *
 * It lives in `data` because none of the three consumers should own it. It was
 * previously a top-level `val` in `FlowTable.kt`, where it had to be declared
 * physically above `DEFAULT_COLUMN_KEYS` or the column catalog read it as null
 * during class initialisation — a constraint that only existed because the two
 * shared a file, and that a separate file removes rather than documents.
 */
val HTTP_METHODS = listOf(
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS",
    "CONNECT", "TRACE",
    "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK",
)
