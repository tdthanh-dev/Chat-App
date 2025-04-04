package com.example.myapplication.controller;

import static androidx.core.content.ContextCompat.startActivity;

import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.myapplication.view.adapter.messagesAdapter;
import com.example.myapplication.view.GroupChatActivity;
import com.example.myapplication.model.msgModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.Date;

public class GroupChatController {
    private final GroupChatActivity groupChatActivity;
    private final FirebaseDatabase database;
    private final String groupId;
    private final ArrayList<msgModel> messagesArrayList;
    private final messagesAdapter messagesAdapter;

    public GroupChatController(GroupChatActivity groupChatActivity, FirebaseDatabase database, String groupId,
            ArrayList<msgModel> messagesArrayList, messagesAdapter messagesAdapter) {
        this.groupChatActivity = groupChatActivity;
        this.database = database;
        this.groupId = groupId;
        this.messagesArrayList = messagesArrayList;
        this.messagesAdapter = messagesAdapter;
    }

    // Khởi tạo cuộc trò chuyện nhóm
    public void initializeGroupChat() {
        DatabaseReference groupChatReference = database.getReference().child("groups").child(groupId).child("messages");
        groupChatReference.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                messagesArrayList.clear();
                for (DataSnapshot dataSnapshot : snapshot.getChildren()) {
                    msgModel message = dataSnapshot.getValue(msgModel.class);
                    messagesArrayList.add(message);
                }
                messagesAdapter.notifyDataSetChanged();

                groupChatActivity.scrollToLastMessage();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(groupChatActivity, "Error loading messages", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Gửi tin nhắn văn bản
    public void sendMessage(String message, String senderUID) {
        if (message.isEmpty()) {
            Toast.makeText(groupChatActivity, "Enter The Message First", Toast.LENGTH_SHORT).show();
            return;
        }

        Date date = new Date();
        msgModel newMessage = new msgModel(message, senderUID, date.getTime(), "text", null, true);

        // Lưu tin nhắn vào Firebase
        database.getReference().child("groups").child(groupId)
                .child("messages").push().setValue(newMessage)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // Gửi thông báo cho các thành viên trong nhóm
                        sendNotificationToGroupMembers(message);
                    } else {
                        Toast.makeText(groupChatActivity, "Failed to send message", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // Gửi thông báo cho các thành viên trong nhóm
    private void sendNotificationToGroupMembers(String message) {
        // Lấy thông tin người gửi và tên nhóm
        String currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Lấy tên người gửi
        DatabaseReference userRef = FirebaseDatabase.getInstance().getReference("user").child(currentUserId);
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String senderName = snapshot.child("fullname").getValue(String.class);
                    if (senderName != null) {
                        // Lấy tên nhóm
                        DatabaseReference groupRef = FirebaseDatabase.getInstance().getReference("groups")
                                .child(groupId);
                        groupRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot groupSnapshot) {
                                if (groupSnapshot.exists()) {
                                    String groupName = groupSnapshot.child("groupName").getValue(String.class);
                                    if (groupName == null) {
                                        groupName = "Nhóm chat";
                                    }

                                    // Gửi thông báo đến tất cả thành viên trong nhóm
                                    NotificationHelper.sendGroupMessageNotification(
                                            groupId,
                                            senderName + " @ " + groupName,
                                            message);
                                }
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {
                                // Xử lý lỗi nếu cần
                            }
                        });
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Xử lý lỗi nếu cần
            }
        });
    }

    // Chọn hình ảnh từ bộ nhớ
    public void selectImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        groupChatActivity.startActivityForResult(intent, 1);
    }

    // Chọn file từ bộ nhớ
    public void selectFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        groupChatActivity.startActivityForResult(intent, 2);
    }

    // Upload hình ảnh hoặc file lên Firebase Storage và gửi thông báo
    public void uploadToFirebaseStorage(Uri fileUri, int requestCode) {
        // Hiển thị ProgressDialog
        ProgressDialog progressDialog = new ProgressDialog(groupChatActivity);
        progressDialog.setMessage("Đang tải...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        String fileName = getFileName(fileUri);
        StorageReference storageReference = FirebaseStorage.getInstance().getReference().child("group_uploads");
        StorageReference filePath = storageReference.child(System.currentTimeMillis() + "");

        filePath.putFile(fileUri).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                filePath.getDownloadUrl().addOnCompleteListener(task1 -> {
                    if (task1.isSuccessful()) {
                        String downloadUrl = task1.getResult().toString();

                        // Gửi tin nhắn với file
                        String messageType = (requestCode == 1) ? "image" : "file";
                        Date date = new Date();
                        msgModel newMessage = new msgModel(downloadUrl, groupChatActivity.senderUID, date.getTime(),
                                messageType, fileName, true);

                        database.getReference().child("groups").child(groupId)
                                .child("messages").push().setValue(newMessage)
                                .addOnCompleteListener(sendTask -> {
                                    if (sendTask.isSuccessful()) {
                                        // Gửi thông báo với nội dung phù hợp
                                        String notificationMessage = messageType.equals("image") ? "Đã gửi hình ảnh"
                                                : "Đã gửi file: " + fileName;
                                        sendNotificationToGroupMembers(notificationMessage);
                                    }
                                });
                    }
                    progressDialog.dismiss();
                });
            } else {
                progressDialog.dismiss();
                Toast.makeText(groupChatActivity, "Failed to upload file", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Lấy tên file từ URI
    private String getFileName(Uri uri) {
        String fileName = "";
        if (uri.getScheme().equals("content")) {
            Cursor cursor = groupChatActivity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                fileName = cursor.getString(nameIndex);
                cursor.close();
            }
        } else {
            fileName = uri.getLastPathSegment();
        }
        return fileName != null ? fileName : "unknown_file";
    }

    public void settingGroup() {
        Intent intent = groupChatActivity.nextStingActivity();
        intent.putExtra("groupId", groupId);
        groupChatActivity.startActivity(intent);
    }
}
