import React from "react";

export interface CommentProps {
  author: string;
  body: string;
  postedAt: string;
}

export function Comment({ author, body, postedAt }: CommentProps) {
  return (
    <article className="comment">
      <header>
        <strong>{author}</strong> <time>{postedAt}</time>
      </header>
      <div className="comment-body" dangerouslySetInnerHTML={{ __html: body }} />
    </article>
  );
}
