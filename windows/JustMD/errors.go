package main

import "errors"

// 领域错误。前端依据错误文本给出业务化提示（NFR-USE-04），不裸露技术调用栈。
var (
	ErrNoteNotFound   = errors.New("笔记不存在或已被删除")
	ErrTitleEmpty     = errors.New("标题不能为空")
	ErrTitleTooLong   = errors.New("标题过长，最多 250 个字节")
	ErrTitleInvalid   = errors.New("标题包含非法字符")
	ErrNoteConflict   = errors.New("同名笔记已存在")
	ErrPathOutside    = errors.New("路径越出知识库范围")
	ErrIndexCorrupted = errors.New("索引损坏，请重建索引")
	ErrSaveFailed     = errors.New("保存失败：无法写入文件，请检查磁盘空间与权限")
	ErrExternalEdit   = errors.New("笔记已被其他程序修改，为避免覆盖已中止保存")
	// ErrStaleRevision 表示本次保存携带的版本比磁盘上的版本旧：
	// 另一次保存已先落盘，这一份是迟到的旧快照，不应再覆盖新内容。
	ErrStaleRevision = errors.New("本次保存的内容已过期（另有更新的内容已保存），已中止覆盖")
	// ErrNoteVanished 表示笔记的正文文件已被外部删除，内存索引里的这条
	// 记录已无对应文件（僵尸笔记），需要从索引中移除。
	ErrNoteVanished = errors.New("笔记文件已被外部删除")
	ErrNoWorkspace  = errors.New("尚未选择知识库文件夹")
)
