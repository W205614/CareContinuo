create index inbox_recipient_recent on inbox_message(recipient_user_id, created_at desc, id desc);

create index outbox_followup_replacement on outbox_event(episode_id, object_id, revision_id)
    where kind = 'FOLLOWUP_CORRECTION';
